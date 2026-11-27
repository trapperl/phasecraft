package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.phased.PhasedLights;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * The game rule phasecraft:phased_lights. One test only: it changes the rule, which holds for the whole
 * server, so it runs in a batch of its own (the test environment, which starts with the rule off).
 */
public class PhasedLightRuleGameTests {
    static final String RULE_OFF = "phasecraft-test:phased_lights_off";
    private static final BlockPos SPOT = new BlockPos(3, 2, 3);

    private static void setRule(GameTestHelper helper, boolean value) {
        helper.getLevel().getGameRules().set(PhasedLights.RULE, value, helper.getLevel().getServer());
    }

    private static void assertLight(GameTestHelper helper, int expected, String what) {
        helper.assertValueEqual(helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(SPOT)), expected, what);
    }

    /**
     * With the rule off a phased glowstone gives the visible world no light, and a player standing next to it is
     * sent nothing; turned on while the server runs, the light comes and the player is sent it; turned off again,
     * both go.
     */
    @GameTest(environment = RULE_OFF, maxTicks = 2400)
    public void ruleSwitchesPhasedLightOffAndOn(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.assertFalse(helper.getLevel().getGameRules().get(PhasedLights.RULE), "the test environment turns the rule off");
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        hidden.setBlock(at.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState());
        hidden.setBlock(at, Blocks.GLOWSTONE.defaultBlockState());
        ServerPlayer player = TestPlayers.join(helper, GameType.ADVENTURE);
        player.teleport(new TeleportTransition(helper.getLevel(), Vec3.atBottomCenterOf(at.south(2)), Vec3.ZERO, 180, 0,
                TeleportTransition.DO_NOTHING));

        helper.startSequence()
                .thenIdle(200)
                .thenExecute(() -> {
                    assertLight(helper, 0, "visible light at a phased glowstone with the rule off");
                    helper.assertTrue(PhasedLights.lightsFor(player).isEmpty(), "a player was sent phased lights with the rule off");
                    setRule(helper, true);
                })
                .thenWaitUntil(() -> assertLight(helper, 15, "visible light at the phased glowstone after the rule was turned on"))
                .thenExecute(() -> {
                    helper.assertTrue(PhasedLights.lightsFor(player).contains(new PhasedLights.Light(at, 15)),
                            "the player next to it is not sent the light with the rule on: " + PhasedLights.lightsFor(player));
                    setRule(helper, false);
                })
                .thenWaitUntil(() -> assertLight(helper, 0, "visible light at the phased glowstone after the rule was turned off"))
                .thenExecute(() -> {
                    helper.assertTrue(PhasedLights.lightsFor(player).isEmpty(), "a player is still sent phased lights after the rule was turned off");
                    TestPlayers.leave(helper, player);
                })
                .thenSucceed();
    }
}
