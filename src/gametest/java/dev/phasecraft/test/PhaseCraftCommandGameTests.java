package dev.phasecraft.test;

import dev.phasecraft.layer.PhaseLayerData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;

/**
 * The /phasecraft commands, run through the real command pipeline: the same
 * steps as the manual test in docs/GETTING_STARTED.md.
 */
public class PhaseCraftCommandGameTests {
    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** set, get and clear behave as documented, and never touch the visible block. */
    @GameTest
    public void setGetClearRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Blocks.STONE);

        CommandProbe.Result set = CommandProbe.asOperator(level, "/phasecraft set " + coords(pos) + " redstone_wire");
        helper.assertTrue(set.succeeded(), "set failed: " + set.lastMessage());
        helper.assertBlockPresent(Blocks.STONE, relative);

        CommandProbe.Result get = CommandProbe.asOperator(level, "/phasecraft get " + coords(pos));
        helper.assertTrue(get.succeeded(), "get failed: " + get.lastMessage());
        helper.assertValueEqual(get.lastMessage(),
                "At " + pos.toShortString() + " | visible: minecraft:stone | hidden: minecraft:redstone_wire",
                "get output");

        CommandProbe.Result clear = CommandProbe.asOperator(level, "/phasecraft clear " + coords(pos));
        helper.assertTrue(clear.succeeded(), "clear failed: " + clear.lastMessage());
        helper.assertValueEqual(CommandProbe.asOperator(level, "/phasecraft get " + coords(pos)).lastMessage(),
                "At " + pos.toShortString() + " | visible: minecraft:stone | hidden: minecraft:air",
                "get output after clear");
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    /** An unknown block name is refused and nothing is stored. */
    @GameTest
    public void unknownBlockIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));

        CommandProbe.Result set = CommandProbe.asOperator(level, "/phasecraft set " + coords(pos) + " not_a_block");
        helper.assertFalse(set.succeeded(), "unknown block was accepted");
        helper.assertValueEqual(set.lastMessage(), "Unknown block ID: not_a_block", "error message");
        helper.assertValueEqual(PhaseLayerData.get(level).getBlock(pos), "minecraft:air", "stored block");
        helper.succeed();
    }

    /** Only operators (level 2, like /setblock) may use /phasecraft. */
    @GameTest
    public void requiresOperatorLevelTwo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 1, 4));
        String command = "/phasecraft set " + coords(pos) + " redstone_wire";

        for (LevelBasedPermissionSet tooLow : new LevelBasedPermissionSet[] {
                LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.MODERATOR}) {
            CommandProbe.Result denied = CommandProbe.run(level, tooLow, command);
            helper.assertFalse(denied.succeeded(), "command ran with " + tooLow);
            helper.assertValueEqual(PhaseLayerData.get(level).getBlock(pos), "minecraft:air",
                    "stored block after denied command");
        }

        helper.assertTrue(CommandProbe.asOperator(level, command).succeeded(), "operator was refused");
        PhaseLayerData.get(level).removeBlock(pos);
        helper.succeed();
    }
}
