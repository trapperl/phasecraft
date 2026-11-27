package dev.phasecraft.compat;

import dev.phasecraft.PhaseCraft;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Claim mods that do not answer Patbox's Common Protection API themselves get an answer from PhaseCraft,
 * through the mod's own API: one adapter class per mod, compiled against the mod and touched only here,
 * only when that mod is installed (so nothing of it is loaded otherwise). Editing mode
 * ({@link dev.phasecraft.edit.Protection}) and every other mod asking the Common Protection API then hear
 * that mod's claims. See "Claims hold for the hidden layer" in docs/ARCHITECTURE.md.
 */
public final class ProtectionAdapters implements ModInitializer {
    /** Open Parties and Claims' mod id. */
    public static final String OPAC = "openpartiesandclaims";

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded(OPAC)) {
            OpacProtection.register();
            PhaseCraft.LOGGER.info("Open Parties and Claims found: its claims hold for the hidden layer");
        }
    }
}
