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

import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class Utils {

    private static final Set<OpenOption> OPTIONS;
    static {
        HashSet<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.CREATE);
        options.add(StandardOpenOption.TRUNCATE_EXISTING);
        options.add(StandardOpenOption.READ);
        options.add(StandardOpenOption.WRITE);
        OPTIONS = Collections.unmodifiableSet(options);
    }

    private Utils() {
        // No instance for you
    }

    public static String getKeyAlgorithm(int algo) {
        switch (algo) {
            case PublicKeyAlgorithmTags.RSA_GENERAL:
            case PublicKeyAlgorithmTags.RSA_ENCRYPT:
            case PublicKeyAlgorithmTags.RSA_SIGN:
                return "RSA";
            case PublicKeyAlgorithmTags.DSA:
                return "DSA";
            case PublicKeyAlgorithmTags.ECDH:
                return "ECDH";
            case PublicKeyAlgorithmTags.ECDSA:
                return "ECDSA";
            case PublicKeyAlgorithmTags.ELGAMAL_GENERAL:
            case PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT:
                return "ELGAMAL";
            case PublicKeyAlgorithmTags.DIFFIE_HELLMAN:
                return "DIFFIE_HELLMAN";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Blindly fetch bytes from the specified URL, write to the given destination,
     * and then return the open {@link FileChannel} back.
     * @param src The source URL of the mod to download
     * @param dst the destination path of the mod to save
     * @return An open {@link FileChannel} with {@link StandardOpenOption#READ} set,
     *         position at zero.
     * @throws IOException thrown if download fail
     */
    public static FileChannel fetch(URL src, Path dst) throws IOException {
        return fetch(src.openStream(), dst);
    }

    /**
     * Blindly fetch bytes from the input stream, write to the given destination,
     * and then return the open {@link FileChannel} back.
     * @param src The source stream of the mod to download
     * @param dst the destination path of the mod to save
     * @return An open {@link FileChannel} with {@link StandardOpenOption#READ} set,
     *         position at zero.
     * @throws IOException thrown if download fail
     * @see <a href="https://stackoverflow.com/a/921400">Referred code on Stack Overflow</a>
     */
    public static FileChannel fetch(InputStream src, Path dst) throws IOException {
        final FileChannel dstChannel = FileChannel.open(dst, OPTIONS);
        try (ReadableByteChannel srcChannel = Channels.newChannel(src)) {
            // TODO do we need a larger value for the max. bytes to transfer?
            dstChannel.transferFrom(srcChannel, 0, Integer.MAX_VALUE);
            return dstChannel;
        }
    }

    /**
     * Open a {@link FileChannel} for the given {@code target}, download it first
     * if it does not exist yet.
     * @param target The path to the file
     * @param src Fallback URL to download from if target does not exist
     */
    public static void downloadIfMissing(Path target, URL src) {
        if (!Files.exists(target)) {
            try {
                fetch(src, target).close();
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // No-op
                }
            }
        }
    }

}
