package org.teacon.sync.sentinel;

import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;

@Mod("remote_sync_sentinel")
public class RemoteSyncSentinel {

    public RemoteSyncSentinel() {
        FMLJavaModLoadingContext.get()
                .getModEventBus()
                .addListener(EventPriority.NORMAL, false, FMLCommonSetupEvent.class, RemoteSyncSentinel::setup);
    }

    public static void setup(FMLCommonSetupEvent event) {
        if (Boolean.getBoolean("org.teacon.sync.failed")) {
            IModInfo modInfo = ModList.get().getModContainerById("remote_sync_sentinel")
                    .map(ModContainer::getModInfo)
                    .orElseThrow(() -> new IllegalStateException("ModInfo absent while mod itself is present"));
            ModLoader.get().addWarning(new ModLoadingWarning(modInfo, ModLoadingStage.COMMON_SETUP, "remote_sync.warn.incomplete"));
        }
    }
}
