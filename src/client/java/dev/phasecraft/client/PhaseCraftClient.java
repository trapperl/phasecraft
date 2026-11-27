package dev.phasecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.phasecraft.PhaseCraft;
import dev.phasecraft.layer.Access;
import dev.phasecraft.overlay.OverlayPayloads;
import dev.phasecraft.phased.PhasedLights;
import dev.phasecraft.piston.PhasePistonAnimation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client side of PhaseCraft: the hidden-layer overlay.
 *
 * <p>Keys (rebindable under Controls, "PhaseCraft"): H shows or hides the
 * overlay, ] and [ make the ghosts more or less opaque, J turns editing mode
 * on and off (clicks go to the hidden layer; see {@link ClientEditing}). The
 * server decides what the client may see and do ({@code OverlaySync},
 * {@code HiddenEditing}).</p>
 */
public final class PhaseCraftClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "overlay"));
    public static final KeyMapping TOGGLE_OVERLAY = new KeyMapping("key.phasecraft.toggle_overlay",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY);
    public static final KeyMapping MORE_OPAQUE = new KeyMapping("key.phasecraft.overlay_more_opaque",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_RBRACKET, CATEGORY);
    public static final KeyMapping TOGGLE_EDITING = new KeyMapping("key.phasecraft.toggle_editing",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY);
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
        KeyMappingHelper.registerKeyMapping(TOGGLE_EDITING);

        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Chunk.TYPE,
                (payload, context) -> LAYER.replaceChunk(payload.chunk(), payload.ghosts()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Blocks.TYPE,
                (payload, context) -> LAYER.apply(payload.ghosts()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Forget.TYPE,
                (payload, context) -> LAYER.forget(payload.chunk()));
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Reset.TYPE,
                (payload, context) -> LAYER.clear());
        ClientPlayNetworking.registerGlobalReceiver(OverlayPayloads.Holding.TYPE,
                (payload, context) -> payload.counts().forEach(count -> LAYER.setHolding(count.pos(), count.count())));
        ClientPlayNetworking.registerGlobalReceiver(PhasedLights.Sync.TYPE, (payload, context) -> PhasedLightsClient.apply(payload));
        ClientPlayNetworking.registerGlobalReceiver(PhasePistonAnimation.Moved.TYPE, (payload, context) -> PhasePistonAnimationClient.apply(payload));
        ClientPlayNetworking.registerGlobalReceiver(Access.Allowed.TYPE, (payload, context) -> ClientEditing.setAllowed(payload.edit()));
        // A new connection starts empty: nothing from the last world or server
        // may show here. Queued before any of the new connection's payloads.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> {
            LAYER.clear();
            ClientEditing.reset();
            PhasedLights.CLIENT.clear();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            LAYER.clear();
            ClientEditing.reset();
            PhasedLights.CLIENT.clear();
        }));

        ClientTickEvents.END_CLIENT_TICK.register(PhaseCraftClient::handleKeys);
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            GhostRenderer.collect(context, LAYER, settings, ClientEditing.editing());
            ClientEditing.submitOutline(context);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "editing"),
                (graphics, deltaTracker) -> ClientEditing.drawHud(graphics));
    }

    private static void handleKeys(Minecraft minecraft) {
        while (TOGGLE_EDITING.consumeClick()) {
            ClientEditing.toggle(minecraft);
        }
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
