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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.*;
import static org.bouncycastle.bcpg.HashAlgorithmTags.*;
import static org.bouncycastle.bcpg.PublicKeyAlgorithmTags.*;

public final class Utils {

    private static final Logger LOGGER = LogManager.getLogger("RemoteSync");
    private static final Marker MARKER = MarkerManager.getMarker("Downloader");

    private static final Set<OpenOption> OPTIONS = Set.of(CREATE, TRUNCATE_EXISTING, READ, WRITE);

    private Utils() {
        // No instance for you
    }

    public static String getKeyAlgorithm(int algo) {
        return switch (algo) {
            case RSA_GENERAL, RSA_ENCRYPT, RSA_SIGN -> "RSA";
            case DSA -> "DSA";
            case ECDH -> "ECDH";
            case ECDSA -> "ECDSA";
            case EDDSA -> "EDDSA";
            case ELGAMAL_GENERAL, ELGAMAL_ENCRYPT -> "ELGAMAL";
            case DIFFIE_HELLMAN -> "DIFFIE_HELLMAN";
            default -> "UNKNOWN";
        };
    }

    public static String getHashAlgorithm(int algo) {
        return switch (algo) {
            // MD
            case MD2 -> "MD2";
            case MD5 -> "MD5";

            // SHA
            case SHA1 -> "SHA1";
            case SHA224 -> "SHA224";
            case SHA256 -> "SHA256";
            case SHA384 -> "SHA384";
            case SHA512 -> "SHA512";
            case DOUBLE_SHA -> "DOUBLE-SHA";

            // RIPEMD
            // https://en.wikipedia.org/wiki/RIPEMD
            case RIPEMD160 -> "RIPEMD160";

            // Tiger
            // https://en.wikipedia.org/wiki/Tiger_(hash_function)
            case TIGER_192 -> "TIGER192";

            // HAVAL
            // https://en.wikipedia.org/wiki/HAVAL
            case HAVAL_5_160 -> "HAVAL-5-160";
            default -> "UNKNOWN";
        };
    }

    /**
     * Blindly fetch bytes from the specified URL with no timeout, write to the given
     * destination, and then return the open {@link FileChannel} back.
     * @param src The source URL of the mod to download
     * @param dst the destination path of the mod to save
     * @param biasedTowardLocalCache If true, local files are always used regardless of remote updates.
     * @return An open {@link FileChannel} with {@link StandardOpenOption#READ} set,
     *         position at zero.
     * @throws IOException thrown if download fail
     */
    public static FileChannel fetch(URL src, Path dst, boolean biasedTowardLocalCache) throws IOException {
        return fetch(src, dst, 0, biasedTowardLocalCache);
    }

    /**
     * Blindly fetch bytes from the specified URL with the specified timeout, write to
     * the given destination, and then return the open {@link FileChannel} back.
     * @param src The source URL of the mod to download
     * @param dst the destination path of the mod to save
     * @param timeout Time to wait before giving up the connection
     * @param biasedTowardLocalCache If true, local files are always used regardless of remote updates.
     * @return An open {@link FileChannel} with {@link StandardOpenOption#READ} set,
     *         position at zero.
     * @throws IOException thrown if download fail
     */
    public static FileChannel fetch(URL src, Path dst, int timeout, boolean biasedTowardLocalCache) throws IOException {
        LOGGER.debug(MARKER, "Trying to decide how to get {}", src);
        final URLConnection conn = src.openConnection();
        conn.setConnectTimeout(timeout);
        if (Files.exists(dst)) {
            if (biasedTowardLocalCache) {
                LOGGER.debug(MARKER, "Prefer to local copy at {} according to configurations", dst);
                return FileChannel.open(dst, READ);
            }
            conn.setIfModifiedSince(Files.getLastModifiedTime(dst).toMillis());
            try {
                conn.connect();
            } catch (IOException e) {
                // Connection failed, prefer local copy instead
                LOGGER.debug(MARKER, "Failed to connect to {}, fallback to local copy at {}", src, dst);
                return FileChannel.open(dst, READ);
            }
            if (conn instanceof HttpURLConnection) {
                int resp = ((HttpURLConnection) conn).getResponseCode();
                if (resp == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    // If remote does not update, we use local copy.
                    LOGGER.debug(MARKER, "Remote {} does not have updates, prefer use local copy at {}", src, dst);
                    return FileChannel.open(dst, READ);
                } else if (resp >= 400) {
                    LOGGER.warn(MARKER, "Remote {} fails with status code {}, prefer use local copy at {}", src, resp, dst);
                    return FileChannel.open(dst, READ);
                }
            }
        }
        // Otherwise, we have to fetch the newer version.
        // It could very well be a local file, but there are also things like FTP
        // which are pretty rare these days (it's 2020s after all!)
        LOGGER.debug(MARKER, "Fetching remote resource {}", src);
        return fetch(conn.getInputStream(), dst);
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
            dstChannel.position(0L); // Reset position back to start
            return dstChannel;
        }
    }

    /**
     * Asynchronously download the resource to the specified destination if it does
     * not exist yet.
     * @param target The path to the file
     * @param src Fallback URL to download from if target does not exist
     * @param timeout Number of milliseconds to wait before giving up connection
     * @return A {@link CompletableFuture} that represents this task.
     */
    public static CompletableFuture<Void> downloadIfMissingAsync(Path target, URL src, int timeout, boolean biasedTowardLocalCache, Consumer<String> progressFeedback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                progressFeedback.accept("RemoteSync: considering " + target.getFileName());
                return fetch(src, target, timeout, biasedTowardLocalCache);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).handleAsync((fc, e) -> {
            if (e != null) {
                LOGGER.warn(MARKER,"Failed to download {}", src);
                LOGGER.debug(MARKER, "Details: src = {}, dst = {}", src, target, e);
            } else if (fc != null) {
                try {
                    fc.close();
                } catch (IOException ignored) {
                    // No-op
                }
            }
            return null;
        });
    }

}
