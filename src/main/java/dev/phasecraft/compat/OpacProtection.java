package dev.phasecraft.compat;

import eu.pb4.common.protection.api.CommonProtection;
import eu.pb4.common.protection.api.ProtectionProvider;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

/**
 * Open Parties and Claims (OPAC), as a Common Protection API provider: OPAC does not answer that API, so
 * PhaseCraft answers it from OPAC's claims, with OPAC's own server API (read in OPAC 0.33.0 for 26.3).
 * Loaded only by {@link ProtectionAdapters}, only when OPAC is installed.
 *
 * <p><b>With a player</b> the answer is OPAC's own, from the checks its event handlers make for a click on
 * the block at that spot, with messages off: {@code IChunkProtectionAPI.onEntityPlaceBlock} (placing),
 * {@code onBlockInteraction} (breaking; using, with what the main hand holds, as for a click on the top
 * face) and {@code onEntityInteraction} (entities). So the claim's owner, their party and allies as the
 * claim's options say, OPAC's admin and server modes, its per-block exceptions and its item rules all
 * count, and nothing is clicked, placed or broken. OPAC is not told what is being placed, so its answer
 * for placing is the one for "a block": an exception a claim makes for using one particular item there
 * does not allow placing.</p>
 *
 * <p><b>With a profile only</b> (no player: a machine acting for whoever built it, perhaps offline) OPAC
 * has no check for an action, so the answer is what OPAC grants that player by standing, by its UUID:
 * full access to the chunk ({@code hasChunkAccess}: claims off, the chunk unprotected, the owner, or in
 * the group the claim gives full access to, by default the owner's party), or in the group the claim lets
 * interact with blocks ("blocksByPlayers"; for entities, full access only). That is what the player would
 * be told when online, except, on the careful side: OPAC's exceptions for particular blocks, entities and
 * items (a claim that lets everyone use its doors) do not count, nor do the modes a player switches on
 * while online (admin mode, server claim mode, impersonation; and non-ally mode, which would refuse
 * more).</p>
 *
 * <p>OPAC's data belongs to the server thread, and exists from "server about to start" on. Asked from
 * another thread or earlier about a server level, the answer is the careful one: refused, protected. A
 * client level is not OPAC's to answer for (allowed, not protected: the server decides).</p>
 */
public final class OpacProtection implements ProtectionProvider {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("phasecraft", "open_parties_and_claims");
    /** Nobody's UUID: full access for it means full access for everyone. */
    private static final UUID NOBODY = UUID.fromString("5f0a1c7e-9d3b-4e52-8a6f-70c2d1b4e9a3");
    /** Past this many chunks an area is called protected without looking. */
    private static final long AREA_CHUNK_LIMIT = 1 << 16;

    private OpacProtection() {}

    static void register() {
        CommonProtection.register(ID, new OpacProtection());
    }

    /** OPAC's API for a server level, on the server thread, once OPAC has loaded its data; null otherwise. */
    private static @Nullable OpenPACServerAPI api(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) {
            return null;
        }
        try {
            return OpenPACServerAPI.get(server);
        } catch (NullPointerException notLoadedYet) {
            return null;
        }
    }

    /** Whether OPAC gives this player, known by UUID only, the run of the chunk (or of its blocks at least). */
    private static boolean standing(OpenPACServerAPI api, Level level, int chunkX, int chunkZ, UUID id, boolean blocks) {
        Identifier dimension = level.dimension().identifier();
        IChunkProtectionAPI protection = api.getChunkProtection();
        if (protection.hasChunkAccess(id, dimension, chunkX, chunkZ)) {
            return true;
        }
        if (!blocks) {
            return false;
        }
        IPlayerConfigAPI config = protection.getConfig(api.getServerClaimsManager().get(dimension, chunkX, chunkZ), dimension);
        return protection.checkPlayerGroupExceptionOption(PlayerConfigOptions.CLAIM_EXCEPTION_BLOCKS_BY_PLAYERS, config, id);
    }

    private static boolean standing(OpenPACServerAPI api, Level level, BlockPos pos, UUID id, boolean blocks) {
        return standing(api, level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()), id, blocks);
    }

    @Override
    public boolean isProtected(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        OpenPACServerAPI api = api(serverLevel);
        return api == null || !standing(api, level, pos, NOBODY, false);
    }

    @Override
    public boolean isAreaProtected(Level level, AABB area) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        OpenPACServerAPI api = api(serverLevel);
        int minX = SectionPos.blockToSectionCoord(Mth.floor(area.minX));
        int maxX = SectionPos.blockToSectionCoord(Mth.floor(area.maxX));
        int minZ = SectionPos.blockToSectionCoord(Mth.floor(area.minZ));
        int maxZ = SectionPos.blockToSectionCoord(Mth.floor(area.maxZ));
        if (api == null || (maxX - minX + 1L) * (maxZ - minZ + 1L) > AREA_CHUNK_LIMIT) {
            return true;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!standing(api, level, x, z, NOBODY, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canPlaceBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        OpenPACServerAPI api = api(serverLevel);
        if (api == null) {
            return false;
        }
        if (player == null) {
            return standing(api, level, pos, profile.id(), true);
        }
        return !api.getChunkProtection().onEntityPlaceBlock(player, serverLevel, pos);
    }

    @Override
    public boolean canBreakBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        return canTouchBlock(level, pos, profile, player, true);
    }

    @Override
    public boolean canInteractBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        return canTouchBlock(level, pos, profile, player, false);
    }

    private static boolean canTouchBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player, boolean breaking) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        OpenPACServerAPI api = api(serverLevel);
        if (api == null) {
            return false;
        }
        if (player == null) {
            return standing(api, level, pos, profile.id(), true);
        }
        // As OPAC's own handlers ask (CommonEvents.onDestroyBlock, onRightClickBlock): the item from the hand,
        // the block at the spot and its exceptions; no messages to the player.
        return !api.getChunkProtection().onBlockInteraction(player, InteractionHand.MAIN_HAND, null, serverLevel, pos,
                Direction.UP, breaking, false, true);
    }

    @Override
    public boolean canInteractEntity(Level level, Entity entity, NameAndId profile, @Nullable Player player) {
        return canTouchEntity(level, entity, profile, player, false);
    }

    @Override
    public boolean canDamageEntity(Level level, Entity entity, NameAndId profile, @Nullable Player player) {
        return canTouchEntity(level, entity, profile, player, true);
    }

    private static boolean canTouchEntity(Level level, Entity entity, NameAndId profile, @Nullable Player player, boolean attack) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        OpenPACServerAPI api = api(serverLevel);
        if (api == null) {
            return false;
        }
        if (player == null) {
            return standing(api, level, entity.blockPosition(), profile.id(), false);
        }
        return !api.getChunkProtection().onEntityInteraction(null, player, entity, null, InteractionHand.MAIN_HAND, attack, false, true);
    }
}
