package dev.phasecraft.test;

import dev.phasecraft.layer.HiddenLayer;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.ProblemReporter;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * No mob is born in the hidden layer (HiddenLayerGuard.mayBeBorn, EntityTypeHiddenSpawnMixin): not from a spawner,
 * a spawn egg, a dispenser, breeding or anything else the game does by itself. Saves, commands, mobs changing into
 * other mobs and dimension travel still bring mobs there. Each test has the visible world beside it doing the same
 * thing, to show the setup does make a mob where one is allowed.
 */
public class HiddenSpawningGameTests {
    private static final Set<EntitySpawnReason> ALLOWED = EnumSet.of(EntitySpawnReason.LOAD, EntitySpawnReason.COMMAND,
            EntitySpawnReason.CONVERSION, EntitySpawnReason.DIMENSION_TRAVEL);

    /** Every reason the game makes a mob for, in both layers; things that are not mobs are left alone. */
    @GameTest
    public void onlySavesCommandsConversionsAndTravelBringMobsIntoTheHiddenLayer(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        ServerLevel hidden = HiddenLayer.of(visible).level();
        for (EntitySpawnReason reason : EntitySpawnReason.values()) {
            Pig inVisible = EntityTypes.PIG.create(visible, reason);
            helper.assertTrue(inVisible != null, "no pig made in the visible world for " + reason);
            Pig inHidden = EntityTypes.PIG.create(hidden, reason);
            helper.assertValueEqual(inHidden != null, ALLOWED.contains(reason), "a pig made in the hidden layer for " + reason);
            helper.assertTrue(EntityTypes.ARMOR_STAND.create(hidden, reason) != null, "no armor stand made in the hidden layer for " + reason);
            helper.assertTrue(EntityTypes.MINECART.create(hidden, reason) != null, "no minecart made in the hidden layer for " + reason);
        }
        helper.succeed();
    }

    /** A pig spawner beside a player makes pigs in the visible world; the same spawner in the hidden layer, with a player inside beside it, makes none. */
    @GameTest(maxTicks = 300)
    public void aHiddenSpawnerMakesNothing(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        HiddenLayer layer = HiddenLayer.of(visible);
        ServerLevel hidden = layer.level();
        BlockPos spawner = helper.absolutePos(new BlockPos(4, 3, 4));
        for (BlockPos pos : BlockPos.betweenClosed(spawner.offset(-5, -2, -5), spawner.offset(5, -2, 5))) {
            visible.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            layer.setBlock(pos.immutable(), Blocks.STONE.defaultBlockState());
        }
        visible.setBlockAndUpdate(spawner, Blocks.SPAWNER.defaultBlockState());
        layer.setBlock(spawner, Blocks.SPAWNER.defaultBlockState());
        makePigs(visible, spawner);
        makePigs(hidden, spawner);
        Vec3 spot = Vec3.atBottomCenterOf(spawner.offset(6, -1, 0));
        ServerPlayer outside = TestPlayers.join(helper, GameType.CREATIVE);
        ServerPlayer inside = TestPlayers.join(helper, GameType.CREATIVE);
        outside.teleport(new TeleportTransition(visible, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        inside.teleport(new TeleportTransition(hidden, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
        AABB around = new AABB(spawner).inflate(8);
        helper.startSequence()
                // One pig is enough: a round that makes only one waits 200 to 800 ticks before the next.
                .thenWaitUntil(() -> helper.assertTrue(pigs(visible, around) >= 1, "the visible spawner made no pigs yet"))
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden.getEntitiesOfClass(Pig.class, around).size(), 0, "pigs made by the hidden spawner");
                    for (Pig pig : visible.getEntitiesOfClass(Pig.class, around)) {
                        pig.discard();
                    }
                    TestPlayers.leave(helper, outside);
                    TestPlayers.leave(helper, inside);
                    layer.setBlock(spawner, Blocks.AIR.defaultBlockState());
                    for (BlockPos pos : BlockPos.betweenClosed(spawner.offset(-5, -2, -5), spawner.offset(5, -2, 5))) {
                        layer.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState());
                    }
                })
                .thenSucceed();
    }

    /** A spawn egg used by a player inside the hidden layer makes nothing; used in the visible world, it makes a pig. */
    @GameTest(maxTicks = 100)
    public void aSpawnEggUsedInsideMakesNothing(GameTestHelper helper) {
        ServerLevel visible = helper.getLevel();
        HiddenLayer layer = HiddenLayer.of(visible);
        ServerLevel hidden = layer.level();
        BlockPos floor = helper.absolutePos(new BlockPos(2, 1, 2));
        visible.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        layer.setBlock(floor, Blocks.STONE.defaultBlockState());
        ServerPlayer player = TestPlayers.join(helper, GameType.CREATIVE);
        Vec3 spot = Vec3.atBottomCenterOf(floor.offset(2, 1, 0));
        AABB around = new AABB(floor).inflate(4);
        try {
            player.teleport(new TeleportTransition(hidden, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            useEgg(player, hidden, floor);
            helper.assertValueEqual(pigs(hidden, around), 0, "pigs from an egg used inside");
            player.teleport(new TeleportTransition(visible, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            useEgg(player, visible, floor);
            helper.assertValueEqual(pigs(visible, around), 1, "pigs from an egg used in the visible world");
            visible.getEntitiesOfClass(Pig.class, around).forEach(Entity::discard);
        } finally {
            TestPlayers.leave(helper, player);
            layer.setBlock(floor, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    private static void makePigs(ServerLevel level, BlockPos pos) {
        SpawnerBlockEntity spawner = (SpawnerBlockEntity) level.getBlockEntity(pos);
        CompoundTag pig = new CompoundTag();
        pig.putString("id", "minecraft:pig");
        // Light rules of its own, so it does not ask for grass and daylight, as a pig would.
        SpawnData data = new SpawnData(pig,
                Optional.of(new SpawnData.CustomSpawnRules(new InclusiveRange<>(0, 15), new InclusiveRange<>(0, 15))), Optional.empty());
        CompoundTag saved = new CompoundTag();
        saved.put("SpawnData", SpawnData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow());
        spawner.getSpawner().load(level, pos, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
    }

    private static int pigs(ServerLevel level, AABB around) {
        return level.getEntitiesOfClass(Pig.class, around).size();
    }

    private static void useEgg(ServerPlayer player, ServerLevel level, BlockPos floor) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PIG_SPAWN_EGG));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).relative(Direction.UP, 0.5), Direction.UP, floor, false);
        player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
    }
}
