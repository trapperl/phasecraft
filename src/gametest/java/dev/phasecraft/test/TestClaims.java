package dev.phasecraft.test;

import eu.pb4.common.protection.api.CommonProtection;
import eu.pb4.common.protection.api.ProtectionProvider;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * TEST ONLY: a claim mod, as far as PhaseCraft can tell. It answers Patbox's Common Protection API
 * as Flan or Get Off My Lawn do: the spots a test claims belong to one player, and nobody else may
 * place, break or use blocks there (whichever the test picks). As a real claim mod, it knows only
 * claims in the Overworld (where its players made them) and nothing of any hidden dimension: asked
 * about one, it says yes. It answers by the profile asked about, whether or not a player comes with it:
 * a hidden machine asks with its owner's profile and no player (ClaimMachineGameTests).
 */
public final class TestClaims implements ProtectionProvider {
    /** What a claim refuses to everyone but its owner. */
    public enum Action { PLACE, BREAK, USE }

    private record Claim(UUID owner, Set<Action> refused) {}

    private static final Map<BlockPos, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static boolean registered;

    private TestClaims() {}

    /** Claims spots for an owner; everyone else is refused these actions there. */
    public static synchronized void claim(UUID owner, Set<Action> refused, BlockPos... spots) {
        if (!registered) {
            CommonProtection.register(Identifier.fromNamespaceAndPath("phasecraft-test", "claims"), new TestClaims());
            registered = true;
        }
        for (BlockPos spot : spots) {
            CLAIMS.put(spot.immutable(), new Claim(owner, refused));
        }
    }

    public static void unclaim(BlockPos... spots) {
        for (BlockPos spot : spots) {
            CLAIMS.remove(spot);
        }
    }

    private static boolean allows(Level level, BlockPos pos, Action action, NameAndId profile) {
        Claim claim = level.dimension() == Level.OVERWORLD ? CLAIMS.get(pos) : null;
        return claim == null || !claim.refused().contains(action) || claim.owner().equals(profile.id());
    }

    @Override
    public boolean isProtected(Level level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && CLAIMS.containsKey(pos);
    }

    @Override
    public boolean isAreaProtected(Level level, AABB area) {
        return level.dimension() == Level.OVERWORLD && CLAIMS.keySet().stream().anyMatch(pos -> area.intersects(new AABB(pos)));
    }

    @Override
    public boolean canBreakBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        return allows(level, pos, Action.BREAK, profile);
    }

    @Override
    public boolean canPlaceBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        return allows(level, pos, Action.PLACE, profile);
    }

    @Override
    public boolean canInteractBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
        return allows(level, pos, Action.USE, profile);
    }
}
