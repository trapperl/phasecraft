package dev.phasecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.phasecraft.PhaseCraft;
import dev.phasecraft.overlay.OverlayPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client side of PhaseCraft: the hidden-layer overlay.
 *
 * <p>Keys (rebindable under Controls, "PhaseCraft"): H shows or hides the
 * overlay, ] and [ make the ghosts more or less opaque. The server decides
 * what the client may see (see {@code OverlaySync}).</p>
 */
public final class PhaseCraftClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "overlay"));
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping("key.phasecraft.toggle_overlay",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY);
    public static final KeyMapping MORE_OPAQUE = new KeyMapping("key.phasecraft.overlay_more_opaque",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_RBRACKET, CATEGORY);
    public static final KeyMapping LESS_OPAQUE = new KeyMapping("key.phasecraft.overlay_less_opaque",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_LBRACKET, CATEGORY);

    private static final GhostLayer LAYER = new GhostLayer();
    private static OverlaySettings settings;

    /** The hidden blocks this client holds. Client thread only. */
    public static GhostLayer layer() {
        return LAYER;
    }

    public static OverlaySettings settings() {
        return settings;
    }

    @Override
    public void onInitializeClient() {
        settings = OverlaySettings.load();
        KeyMappingHelper.registerKeyMapping(TOGGLE_OVERLAY);
        KeyMappingHelper.registerKeyMapping(MORE_OPAQUE);
        KeyMappingHelper.registerKeyMapping(LESS_OPAQUE);

        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Chunk.TYPE,
                (payload, context) -> LAYER.replaceChunk(payload.chunk(), payload.ghosts()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Blocks.TYPE,
                (payload, context) -> LAYER.apply(payload.ghosts()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Forget.TYPE,
                (payload, context) -> LAYER.forget(payload.chunk()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Reset.TYPE,
                (payload, context) -> LAYER.clear());
        // A new connection starts empty: nothing from the last world or server
        // may show here. Queued before any of the new connection's payloads.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(LAYER::clear));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(LAYER::clear));

        ClientTickEvents.END_CLIENT_TICK.register(PhaseCraftClient::handleKeys);
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> GhostRenderer.collect(context, LAYER, settings));
    }

    private static void handleKeys(Minecraft minecraft) {
        boolean changed = false;
        while (TOGGLE_OVERLAY.consumeClick()) {
            settings.toggle();
            changed = true;
        }
        while (MORE_OPAQUE.consumeClick()) {
            settings.moreOpaque();
            changed = true;
        }
        while (LESS_OPAQUE.consumeClick()) {
            settings.lessOpaque();
            changed = true;
        }
        if (changed && minecraft.player != null) {
            minecraft.player.sendOverlayMessage(settings.enabled()
                    ? Component.translatable("phasecraft.overlay.on", settings.opacityPercent())
                    : Component.translatable("phasecraft.overlay.off"));
        }
    }
}
