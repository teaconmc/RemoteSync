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

import com.google.gson.Gson;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import net.minecraftforge.forgespi.Environment;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public final class SyncedModLocator implements IModLocator {

    private static final Logger LOGGER = LogManager.getLogger(SyncedModLocator.class);

    private static final Gson GSON = new Gson();

    private IModLocator parent;

    private final Path modDirBase;

    private final PGPPublicKeyRingCollection keyRing;

    private final CompletableFuture<Void> fetchPathsTask;

    private final Set<Path> invalidFiles = new HashSet<>();

    public SyncedModLocator() throws Exception {
        final Path gameDir = Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("."));
        final Config cfg = GSON.fromJson(Files.newBufferedReader(gameDir.resolve("remote_sync.json"), StandardCharsets.UTF_8), Config.class);
        this.keyRing = new BcPGPPublicKeyRingCollection(PGPUtil.getDecoderStream(Files.newInputStream(gameDir.resolve(cfg.keyRingPath))));
        for (PGPPublicKeyRing ring : this.keyRing) {
            for (PGPPublicKey pubKey : ring) {
                LOGGER.printf(Level.DEBUG, "Public Key ID = %1$016X, Algo = %2$s, Fingerprint = %3$s",
                        pubKey.getKeyID(), Utils.getKeyAlgorithm(pubKey.getAlgorithm()), Hex.toHexString(pubKey.getFingerprint()));
            }
        }
        this.modDirBase = Files.createDirectories(gameDir.resolve(cfg.modDir));
        this.fetchPathsTask = CompletableFuture.supplyAsync(() -> {
            try {
                return Utils.fetch(cfg.modList, gameDir.resolve("mod_list.json"), cfg.timeout);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).handleAsync((fcModList, exc) -> {
            if (exc != null) {
                return new ModEntry[0];
            }
            try (Reader reader = Channels.newReader(fcModList, "UTF-8")) {
                return GSON.fromJson(reader, ModEntry[].class);
            } catch (IOException e) {
                LOGGER.warn("Failed to fetch mod list from remote", e);
                return new ModEntry[0];
            }
        }).thenCompose(entries -> {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (ModEntry e : entries) {
                tasks.add(CompletableFuture.allOf(
                        CompletableFuture.runAsync(() -> Utils.downloadIfMissing(this.modDirBase.resolve(e.name), e.file, cfg.timeout)),
                        CompletableFuture.runAsync(() -> Utils.downloadIfMissing(this.modDirBase.resolve(e.name + ".sig"), e.file, cfg.timeout))
                ).exceptionally(t -> {
                    LOGGER.warn("Failed to download {}", e.name);
                    LOGGER.debug("Details: src = {}, dst = {}", e.file, e.name, t);
                    return null;
                }));
            }
            return CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0]));
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path p : this.invalidFiles) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // No-op
                }
            }
        }, "RemoteSync Clean-up"));
    }

    private static PGPSignatureList getSigList(FileChannel fc) throws Exception {
        PGPSignatureList sigList;
        try (InputStream input = PGPUtil.getDecoderStream(Channels.newInputStream(fc))) {
            BcPGPObjectFactory factory = new BcPGPObjectFactory(input);
            Object o = factory.nextObject();
            if (o instanceof PGPCompressedData) {
                PGPCompressedData compressedData = (PGPCompressedData)o;
                factory = new BcPGPObjectFactory(compressedData.getDataStream());
                sigList = (PGPSignatureList)factory.nextObject();
            } else {
                sigList = (PGPSignatureList)o;
            }
        }
        return sigList;
    }

    private static boolean verifyDetached(FileChannel src, PGPSignatureList sigList, PGPPublicKeyRingCollection keyRing) {
        for (PGPSignature sig : sigList) {
            try {
                sig.init(new BcPGPContentVerifierBuilderProvider(), keyRing.getPublicKey(sig.getKeyID()));
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
                    LOGGER.printf(Level.WARN, "Signature verification failed (%1$s key %2$016X, made on %3$tc)",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                    return false;
                } else {
                    LOGGER.printf(Level.DEBUG, "Signature verified: %1$s key %2$016X, made on %3$tc",
                            PGPUtil.getSignatureName(sig.getKeyAlgorithm(), sig.getHashAlgorithm()), sig.getKeyID(), sig.getCreationTime());
                }
            } catch (PGPException e) {
                LOGGER.printf(Level.WARN, "Cannot find key %1$016X in current key ring, or the key/hash algorithm is unknown/unsupported", sig.getKeyID());
                return false;
            } catch (IOException e) {
                LOGGER.warn("Failed to read file while checking signature", e);
                return false;
            }
        }
        return true;
    }

    @Override
    public List<IModFile> scanMods() {
        try {
            this.fetchPathsTask.join();
            return this.parent.scanMods()
                    .stream()
                    // Work around https://github.com/MinecraftForge/MinecraftForge/issues/6756
                    // The signature check MUST be in the place for best-effort safety
                    .filter(this::isValid)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.error("Mod downloading worker encountered error and cannot continue. " +
                    "No mod will be loaded from the remote-synced locator. ", e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "Remote Synced";
    }

    @Override
    public Path findPath(IModFile modFile, String... path) {
        return this.parent.findPath(modFile, path);
    }

    @Override
    public void scanFile(IModFile modFile, Consumer<Path> pathConsumer) {
        this.parent.scanFile(modFile, pathConsumer);
    }

    @Override
    public Optional<Manifest> findManifest(Path file) {
        return this.parent.findManifest(file);
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        this.parent = Launcher.INSTANCE.environment()
                .getProperty(Environment.Keys.MODDIRECTORYFACTORY.get())
                .orElseThrow(() -> new RuntimeException("Cannot find ModDirectoryFactory"))
                .build(this.modDirBase, "Remote Synced Backend");
    }

    @Override
    public boolean isValid(IModFile modFile) {
        LOGGER.debug("Verifying {}", modFile.getFileName());
        final Path modPath = modFile.getFilePath();
        final Path sigPath = modPath.resolveSibling(modFile.getFileName() + ".sig");
        try (FileChannel mod = FileChannel.open(modPath, StandardOpenOption.READ)) {
            try (FileChannel sig = FileChannel.open(sigPath, StandardOpenOption.READ)) {
                final PGPSignatureList sigList;
                try {
                    sigList = getSigList(sig);
                } catch (Exception e) {
                    LOGGER.warn("Failed to read signature for {}, verification automatically fails", modFile.getFileName());
                    return false;
                }
                final boolean pass = verifyDetached(mod, sigList, this.keyRing);
                if (pass) {
                    LOGGER.debug("Verification pass for {}", modFile.getFileName());
                } else {
                    LOGGER.warn("Verification fail for {}, will be excluded from loading", modFile.getFileName());
                    this.invalidFiles.add(modPath.toAbsolutePath().normalize());
                    this.invalidFiles.add(sigPath.toAbsolutePath().normalize());
                }
                return pass;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}, verification automatically fails", modFile.getFileName());
            return false;
        }
    }

}
