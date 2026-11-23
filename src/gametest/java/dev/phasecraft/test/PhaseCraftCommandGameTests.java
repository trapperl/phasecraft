package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The /phasecraft commands, run through the real command pipeline: the same
 * steps as the manual test in docs/GETTING_STARTED.md.
 */
public class PhaseCraftCommandGameTests {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String getLine(BlockPos pos, BlockState visible, BlockState hidden) {
        return "At " + pos.toShortString() + " | visible: " + BlockStateParser.serialize(visible)
                + " | hidden: " + BlockStateParser.serialize(hidden);
    }

    /** set, get and clear behave as documented, and never touch the visible block. */
    @GameTest
    public void setGetClearRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(relative);
        BlockState stone = Blocks.STONE.defaultBlockState();
        helper.setBlock(relative, Blocks.STONE);
        HiddenLayer.of(level).setBlock(pos.below(), stone);

        CommandProbe.Result set = CommandProbe.asOperator(level, "/phasecraft set " + coords(pos) + " redstone_wire");
        helper.assertTrue(set.succeeded(), "set failed: " + set.lastMessage());
        helper.assertBlockPresent(Blocks.STONE, relative);

        CommandProbe.Result get = CommandProbe.asOperator(level, "/phasecraft get " + coords(pos));
        helper.assertTrue(get.succeeded(), "get failed: " + get.lastMessage());
        helper.assertValueEqual(get.lastMessage(),
                getLine(pos, stone, Blocks.REDSTONE_WIRE.defaultBlockState()), "get output");

        CommandProbe.Result clear = CommandProbe.asOperator(level, "/phasecraft clear " + coords(pos));
        helper.assertTrue(clear.succeeded(), "clear failed: " + clear.lastMessage());
        helper.assertValueEqual(CommandProbe.asOperator(level, "/phasecraft get " + coords(pos)).lastMessage(),
                getLine(pos, stone, AIR), "get output after clear");
        helper.assertBlockPresent(Blocks.STONE, relative);
        HiddenLayer.of(level).removeBlock(pos.below());
        helper.succeed();
    }

    /** Namespaced IDs work, as they do in /setblock. */
    @GameTest
    public void namespacedBlockIdIsAccepted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(5, 1, 5));
        HiddenLayer.of(level).setBlock(pos.below(), Blocks.STONE.defaultBlockState());

        CommandProbe.Result set = CommandProbe.asOperator(level,
                "/phasecraft set " + coords(pos) + " minecraft:redstone_wire");
        helper.assertTrue(set.succeeded(), "namespaced set failed: " + set.lastMessage());
        helper.assertValueEqual(HiddenLayer.of(level).getBlock(pos),
                Blocks.REDSTONE_WIRE.defaultBlockState(), "stored block");

        HiddenLayer.of(level).removeBlock(pos);
        HiddenLayer.of(level).removeBlock(pos.below());
        helper.succeed();
    }

    /** Block-state properties given in the command are stored and shown by get. */
    @GameTest
    public void blockStatePropertiesAreStored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(6, 1, 6));
        HiddenLayer.of(level).setBlock(pos.below(), Blocks.STONE.defaultBlockState());
        BlockState repeater = Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.DELAY, 3);

        CommandProbe.Result set = CommandProbe.asOperator(level,
                "/phasecraft set " + coords(pos) + " repeater[facing=east,delay=3]");
        helper.assertTrue(set.succeeded(), "set with properties failed: " + set.lastMessage());
        helper.assertValueEqual(HiddenLayer.of(level).getBlock(pos), repeater, "stored repeater");
        helper.assertValueEqual(CommandProbe.asOperator(level, "/phasecraft get " + coords(pos)).lastMessage(),
                getLine(pos, level.getBlockState(pos), repeater), "get output");

        HiddenLayer.of(level).removeBlock(pos);
        HiddenLayer.of(level).removeBlock(pos.below());
        helper.succeed();
    }

    /** An unknown block name is refused and nothing is stored. */
    @GameTest
    public void unknownBlockIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));

        CommandProbe.Result set = CommandProbe.asOperator(level, "/phasecraft set " + coords(pos) + " not_a_block");
        helper.assertFalse(set.succeeded(), "unknown block was accepted");
        helper.assertTrue(set.lastMessage().contains("not_a_block"),
                "error does not name the bad block: " + set.lastMessage());
        helper.assertValueEqual(HiddenLayer.of(level).getBlock(pos), AIR, "stored block");
        helper.succeed();
    }

    /** Positions outside the world's height range are refused, not silently ignored. */
    @GameTest
    public void positionOutsideWorldHeightIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos above = new BlockPos(helper.absolutePos(BlockPos.ZERO).getX(), level.getMaxY() + 10, 0);

        CommandProbe.Result set = CommandProbe.asOperator(level, "/phasecraft set " + coords(above) + " stone");
        helper.assertFalse(set.succeeded(), "set outside the world height reported success");
        helper.assertTrue(set.lastMessage().contains("outside"), "refusal does not explain: " + set.lastMessage());
        helper.succeed();
    }

    /** /phasecraft link reports the cross-layer update counters. */
    @GameTest
    public void linkCommandReportsCounters(GameTestHelper helper) {
        CommandProbe.Result link = CommandProbe.asOperator(helper.getLevel(), "/phasecraft link");
        helper.assertTrue(link.succeeded(), "link command failed: " + link.lastMessage());
        helper.assertTrue(link.lastMessage().matches("Phase link: pending=\\d+ queued=\\d+ delivered=\\d+ portsRefreshed=\\d+ skippedUnloaded=\\d+"),
                "unexpected output: " + link.lastMessage());
        helper.succeed();
    }

    /** Only operators (level 2, like /setblock) may use /phasecraft. */
    @GameTest
    public void requiresOperatorLevelTwo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 1, 4));
        String command = "/phasecraft set " + coords(pos) + " redstone_wire";
        HiddenLayer.of(level).setBlock(pos.below(), Blocks.STONE.defaultBlockState());

        for (LevelBasedPermissionSet tooLow : new LevelBasedPermissionSet[] {
                LevelBasedPermissionSet.ALL, LevelBasedPermissionSet.MODERATOR}) {
            CommandProbe.Result denied = CommandProbe.run(level, tooLow, command);
            helper.assertFalse(denied.succeeded(), "command ran with " + tooLow);
            helper.assertValueEqual(HiddenLayer.of(level).getBlock(pos), AIR,
                    "stored block after denied command");
        }

        helper.assertTrue(CommandProbe.asOperator(level, command).succeeded(), "operator was refused");
        helper.assertValueEqual(HiddenLayer.of(level).getBlock(pos), Blocks.REDSTONE_WIRE.defaultBlockState(),
                "stored block after operator command");
        HiddenLayer.of(level).removeBlock(pos);
        HiddenLayer.of(level).removeBlock(pos.below());
        helper.succeed();
    }
}
