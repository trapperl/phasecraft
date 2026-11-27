package dev.phasecraft.test;

import dev.phasecraft.edit.AimCheck;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Editing mode shows a protected spot before it is clicked (AimCheck): the server says what would refuse an edit at
 * the spot aimed at. The client side (the red outline and the readout) is EditingClientGameTest's.
 */
public class AimCheckGameTests {
    /** A claim, spawn protection, the world border, or nothing: each is told apart, for hidden and visible targets. */
    @GameTest
    public void aimSaysWhyAnEditWouldBeRefused(GameTestHelper helper) {
        ServerPlayer player = TestPlayers.join(helper, GameType.CREATIVE);
        BlockPos free = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos noBreaking = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos noPlacing = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos spawn = helper.absolutePos(new BlockPos(4, 2, 1));
        BlockPos outside = new BlockPos(40_000_000, 64, 0);
        UUID owner = UUID.randomUUID();
        TestClaims.claim(owner, Set.of(TestClaims.Action.BREAK), noBreaking);
        TestClaims.claim(owner, Set.of(TestClaims.Action.PLACE), noPlacing);
        TestSpawnProtection.PROTECTED.add(spawn);
        try {
            helper.assertValueEqual(AimCheck.why(player, free, true), AimCheck.FREE, "a free hidden block");
            helper.assertValueEqual(AimCheck.why(player, free, false), AimCheck.FREE, "a free visible spot");
            helper.assertValueEqual(AimCheck.why(player, noBreaking, true), AimCheck.CLAIM, "a hidden block where a claim refuses breaking");
            helper.assertValueEqual(AimCheck.why(player, noBreaking, false), AimCheck.FREE, "a visible spot where a claim refuses only breaking");
            helper.assertValueEqual(AimCheck.why(player, noPlacing, false), AimCheck.CLAIM, "a visible spot where a claim refuses placing");
            helper.assertValueEqual(AimCheck.why(player, spawn, true), AimCheck.SPAWN, "a spawn-protected spot");
            helper.assertValueEqual(AimCheck.why(player, outside, false), AimCheck.BORDER, "a spot outside the world border");
            for (int why : new int[] {AimCheck.CLAIM, AimCheck.SPAWN, AimCheck.BORDER}) {
                String key = ((net.minecraft.network.chat.contents.TranslatableContents) AimCheck.reason(why).getContents()).getKey();
                helper.assertTrue(Language.getInstance().has(key), "no English text for " + key);
            }
        } finally {
            TestClaims.unclaim(noBreaking, noPlacing);
            TestSpawnProtection.PROTECTED.remove(spawn);
            TestPlayers.leave(helper, player);
        }
        helper.succeed();
    }
}
