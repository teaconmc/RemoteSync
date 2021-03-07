/*
 * Copyright (C) 2021 3TUSK
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

// SPDX-Identifier: LGPL-2.1-or-later

package org.teacon.sync;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.util.encoders.Hex;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class PGPKeyStore {

    /*
     * Implementation overview
     *
     * This class implements a partial client that supports retrieving
     * public PGP keys via HTTP Keyserver Protocol (HKP).
     *
     * More info about HKP may be found at this IETF draft:
     * https://tools.ietf.org/html/draft-shaw-openpgp-hkp-00
     */

    private static final Logger LOGGER = LogManager.getLogger("RemoteSync");
    private static final Marker MARKER = MarkerManager.getMarker("KeyStore");

    private static final boolean RESOLVE_SRV_RECORD;

    static {
        boolean resolveSrv = false;
        try {
            Class.forName("com.sun.jndi.dns.DnsContextFactory", false, null);
            resolveSrv = true;
        } catch (Exception ignored) {

        }
        RESOLVE_SRV_RECORD = resolveSrv;
    }

    private final PGPPublicKeyRingCollection keyRings;

    public PGPKeyStore(Path localKeyStorePath, List<URL> keyServers, List<String> keyIds) throws Exception {
        final Map<Long, PGPPublicKeyRing> keyRings = new HashMap<>();
        readKeys(Files.newInputStream(localKeyStorePath), keyRings);
        for (String keyId : keyIds) {
            final String queryParams = "/pks/lookup?op=get&search=".concat(keyId);
            for (URL keyServer : keyServers) {
                final URL resolved = resolveSrv(keyServer);
                final URL keyQuery = new URL(resolved.getProtocol(), resolved.getHost(), resolved.getPort(), queryParams);
                try (InputStream input = keyQuery.openStream()) {
                    readKeys(input, keyRings);
                    break; // Stop on first available key server
                } catch (Exception ignored) {
                    // No-op
                }
            }
        }
        this.keyRings = new PGPPublicKeyRingCollection(keyRings.values());
    }

    public void debugDump() {
        for (PGPPublicKeyRing ring : this.keyRings) {
            for (PGPPublicKey pubKey : ring) {
                LOGGER.printf(Level.DEBUG, MARKER, "Public Key ID = %1$016X, Algo = %2$s, Fingerprint = %3$s",
                        pubKey.getKeyID(), Utils.getKeyAlgorithm(pubKey.getAlgorithm()), Hex.toHexString(pubKey.getFingerprint()));
            }
        }
    }

    public boolean verify(FileChannel src, PGPSignatureList sigList) {
        for (PGPSignature sig : sigList) {
            try {
                PGPPublicKey pubKey = this.keyRings.getPublicKey(sig.getKeyID());
                sig.init(new BcPGPContentVerifierBuilderProvider(), pubKey);
                // Has to be a heap buffer, BouncyCastle only supports passing in byte[]
                ByteBuffer buf = ByteBuffer.allocate(1 << 12);
                src.position(0);
                int limit;
                while ((limit = src.read(buf)) != -1) {
                    buf.flip(); // limit = pos, pos = 0
                    sig.update(buf.array(), 0, limit);
                    buf.clear(); // limit = cap, pos = 0
                }
                if (!sig.verify()) {
                    LOGGER.printf(Level.WARN, MARKER, "Signature verification failed (%1$s key %2$016X, made on %3$tc)",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                    return false;
                } else if (pubKey.hasRevocation()) {
                    LOGGER.printf(Level.WARN, MARKER, "Signature verified (%1$s key %2$016X, made on %3$tc) but the key-pair has been revoked",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                    return false;
                } else if (hasExpired(pubKey)) {
                    LOGGER.printf(Level.WARN, MARKER, "Signature verified (%1$s key %2$016X, made on %3$tc) but the key-pair has expired",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                    return false;
                } else {
                    LOGGER.printf(Level.DEBUG, MARKER, "Signature verified: %1$s key %2$016X, made on %3$tc",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                }
            } catch (PGPException e) {
                LOGGER.printf(Level.WARN, MARKER, "Cannot find key %1$016X in current key ring, or the key/hash algorithm is unknown/unsupported", sig.getKeyID());
                return false;
            } catch (IOException e) {
                LOGGER.warn(MARKER,"Failed to read file while checking signature", e);
                return false;
            }
        }
        return true;
    }

    public void saveTo(Path path) {
        try (OutputStream output = Files.newOutputStream(path)) {
            this.keyRings.encode(output);
        } catch (IOException e) {
            LOGGER.warn(MARKER, "Failed to save key store", e);
        }
    }

    private static void readKeys(InputStream input, Map<Long, PGPPublicKeyRing> keyRings) throws Exception {
        try (InputStream wrapped = PGPUtil.getDecoderStream(input)) {
            PGPObjectFactory factory = new BcPGPObjectFactory(wrapped);
            for (Object o : factory) {
                if (o instanceof PGPPublicKeyRing) {
                    PGPPublicKeyRing keyRing = (PGPPublicKeyRing) o;
                    keyRings.put(keyRing.getPublicKey().getKeyID(), keyRing);
                } else {
                    LOGGER.warn(MARKER, "Invalid PGP object {} (type {}) found and ignored", o, o.getClass());
                }
            }
        }
    }

    private static URL resolveSrv(URL original) {
        if (!RESOLVE_SRV_RECORD) {
            return original;
        }
        /*
         * Perform DNS query via JNDI.
         *
         * Documentation:
         * https://docs.oracle.com/javase/8/docs/technotes/guides/jndi/jndi-dns.html
         */
        try {
            Hashtable<String, String> params = new Hashtable<>();
            params.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
            params.put(Context.PROVIDER_URL, "dns:");
            params.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext context = new InitialDirContext(params);
            // https://tools.ietf.org/html/draft-shaw-openpgp-hkp-00 Chapter 7
            Attributes attrs = context.getAttributes("_hkp._tcp." + original.getHost(), new String[]{"SRV"});
            Attribute attr = attrs.get("srv");
            if (attr != null) {
                String[] results = attr.get().toString().split(" ", 4);
                int port = 80;
                try {
                    port = Integer.parseInt(results[2]);
                } catch (Exception ignored) {

                }
                return new URL(original.getProtocol(), results[3], port, "/");
            }
        } catch (Exception e) {
            // TODO Log error
        }
        return original;
    }

    private static boolean hasExpired(PGPPublicKey pubKey) {
        Instant creationTime = pubKey.getCreationTime().toInstant();
        Instant expirationTime = creationTime.plus(pubKey.getValidSeconds(), ChronoUnit.SECONDS);
        return Instant.now().isAfter(expirationTime);
    }
}
