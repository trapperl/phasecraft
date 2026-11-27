package dev.phasecraft.test;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;

/**
 * TEST ONLY: real Open Parties and Claims claims and parties, made through OPAC's own API as its commands
 * make them. Touched only by {@link OpacClaimChecks}' tests, which run only with OPAC loaded
 * (./gradlew runGameTestOpac): nothing else may name this class, or a game without OPAC fails to load it.
 */
final class OpacClaims {
    private OpacClaims() {}

    private static OpenPACServerAPI api(ServerLevel level) {
        return OpenPACServerAPI.get(level.getServer());
    }

    /** Claims the chunk of this spot for an owner, with the owner's main settings (OPAC's defaults). */
    static void claim(ServerLevel level, BlockPos spot, UUID owner) {
        Identifier dimension = level.dimension().identifier();
        int x = SectionPos.blockToSectionCoord(spot.getX());
        int z = SectionPos.blockToSectionCoord(spot.getZ());
        IPlayerChunkClaimAPI claim = api(level).getServerClaimsManager().claim(dimension, owner, -1, x, z, false);
        if (claim == null || !owner.equals(claim.getPlayerId())) {
            throw new IllegalStateException("OPAC did not claim chunk " + x + ", " + z + " for " + owner + ": " + claim);
        }
    }

    static void unclaim(ServerLevel level, BlockPos spot) {
        api(level).getServerClaimsManager().unclaim(level.dimension().identifier(),
                SectionPos.blockToSectionCoord(spot.getX()), SectionPos.blockToSectionCoord(spot.getZ()));
    }

    /** A party of the owner's with one more member. */
    static void party(ServerLevel level, NameAndId owner, NameAndId member) {
        IServerPartyAPI party = api(level).getPartyManager().createPartyForOwner(owner);
        if (party == null || party.addMember(member.id(), null, member.name()) == null) {
            throw new IllegalStateException("OPAC did not make a party of " + owner + " with " + member);
        }
    }

    static void disband(ServerLevel level, UUID owner) {
        api(level).getPartyManager().removePartyByOwner(owner);
    }
}
