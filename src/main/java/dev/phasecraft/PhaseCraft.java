package dev.phasecraft;

import dev.phasecraft.command.PhaseCraftCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the first PhaseCraft server-side prototype. */
public final class PhaseCraft implements ModInitializer {
    public static final String MOD_ID = "phasecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PhaseCraftCommands.register();
        LOGGER.info("PhaseCraft prototype initialized: virtual layer storage enabled");
    }
}
