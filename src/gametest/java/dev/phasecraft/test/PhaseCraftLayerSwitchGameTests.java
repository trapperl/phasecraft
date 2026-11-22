package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** /phasecraft layer: moving a player between the visible and hidden layer at the same spot. */
public class PhaseCraftLayerSwitchGameTests {
    /** A Creative player goes to the hidden layer and back, keeping position. */
    @GameTest
    public void switchToHiddenAndBackKeepsPosition(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(3, 2, 3)));
            player.teleportTo(spot.x, spot.y, spot.z);
            helper.assertValueEqual(player.level().dimension(), Level.OVERWORLD, "start dimension");

            CommandProbe.Result in = CommandProbe.asPlayer(player, "/phasecraft layer hidden");
            helper.assertTrue(in.succeeded(), "switch to hidden failed: " + in.lastMessage());
            helper.assertValueEqual(player.level().dimension(), HiddenDimensions.HIDDEN_OVERWORLD, "dimension after switch");
            helper.assertTrue(player.position().distanceTo(spot) < 0.01, "position changed: " + player.position());

            CommandProbe.Result again = CommandProbe.asPlayer(player, "/phasecraft layer hidden");
            helper.assertFalse(again.succeeded(), "switching to the layer you are in should be refused");

            CommandProbe.Result out = CommandProbe.asPlayer(player, "/phasecraft layer visible");
            helper.assertTrue(out.succeeded(), "switch back failed: " + out.lastMessage());
            helper.assertValueEqual(player.level(), visible, "level after switching back");
            helper.assertTrue(player.position().distanceTo(spot) < 0.01, "position changed: " + player.position());
        } finally {
            visible.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** The hidden layer has no ground: Survival players are refused instead of falling into the void. */
    @GameTest
    public void survivalPlayersAreRefused(GameTestHelper helper) {
        ServerPlayer survivor = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

        CommandProbe.Result result = CommandProbe.asPlayer(survivor, "/phasecraft layer hidden");
        helper.assertFalse(result.succeeded(), "survival player was let into the hidden layer");
        helper.assertValueEqual(survivor.level().dimension(), Level.OVERWORLD, "survivor's dimension");
        helper.assertTrue(result.lastMessage().contains("Creative"), "refusal does not explain: " + result.lastMessage());
        helper.succeed();
    }
}
