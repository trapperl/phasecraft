package dev.phasecraft;

import dev.phasecraft.command.PhaseCraftCommands;
import dev.phasecraft.edit.HiddenEditing;
import dev.phasecraft.layer.LegacyLayerMigration;
import dev.phasecraft.link.PhaseLink;
import dev.phasecraft.overlay.OverlaySync;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the first PhaseCraft server-side prototype. */
public final class PhaseCraft implements ModInitializer {
    public static final String MOD_ID = "phasecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PhaseCraftBlocks.register();
        PhaseCraftCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(LegacyLayerMigration::migrateAll);
        PhaseLink.register();
        OverlaySync.register();
        HiddenEditing.register();
        LOGGER.info("PhaseCraft prototype initialized: hidden layer dimensions enabled");
    }
}
