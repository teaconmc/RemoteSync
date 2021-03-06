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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
import java.util.stream.Stream;

public final class SyncedModLocator implements IModLocator {

    private static final Logger LOGGER = LogManager.getLogger("RemoteSync");

    private static final Gson GSON = new Gson();

    private IModLocator parent;

    private final Path modDirBase;

    private final PGPKeyStore keyStore;

    private final CompletableFuture<Void> fetchPathsTask;

    private Set<String> allowedFiles = Collections.emptySet();
    private final Set<Path> invalidFiles = new HashSet<>();

    public SyncedModLocator() throws Exception {
        final Path gameDir = Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("."));
        final Config cfg = GSON.fromJson(Files.newBufferedReader(gameDir.resolve("remote_sync.json"), StandardCharsets.UTF_8), Config.class);
        final Path keyStorePath = gameDir.resolve(cfg.keyRingPath);
        this.keyStore = new PGPKeyStore(keyStorePath, cfg.keyServers, cfg.keyIds);
        this.keyStore.debugDump();
        this.modDirBase = Files.createDirectories(gameDir.resolve(cfg.modDir));
        this.fetchPathsTask = CompletableFuture.supplyAsync(() -> {
            try {
                return Utils.fetch(cfg.modList, gameDir.resolve("mod_list.json"), cfg.timeout);
            } catch (IOException e) {
                LOGGER.warn("Failed to download mod list", e);
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
        }).whenComplete((entries, err) -> {
            this.allowedFiles = Arrays.stream(entries).map(e -> e.name).collect(Collectors.toSet());
        }).thenComposeAsync(entries -> CompletableFuture.allOf(
                Arrays.stream(entries).flatMap(e -> Stream.of(
                        Utils.downloadIfMissingAsync(this.modDirBase.resolve(e.name), e.file, cfg.timeout),
                        Utils.downloadIfMissingAsync(this.modDirBase.resolve(e.name + ".sig"), e.sig, cfg.timeout)
                )).toArray(CompletableFuture[]::new)
        ));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path p : this.invalidFiles) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // No-op
                }
            }
            this.keyStore.saveTo(keyStorePath);
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

    @Override
    public List<IModFile> scanMods() {
        try {
            this.fetchPathsTask.join();
            return this.parent.scanMods()
                    .stream()
                    // Work around https://github.com/MinecraftForge/MinecraftForge/issues/6756
                    // The signature check MUST be in the place for best-effort safety
                    .filter(this::isValid)
                    .filter(mod -> this.allowedFiles.contains(mod.getFileName()))
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
                if (sigList == null) {
                    LOGGER.warn("Failed to load any signature for {}, check if you downloaded the wrong file", modFile.getFileName());
                    return false;
                }
                final boolean pass = this.keyStore.verify(mod, sigList);
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
