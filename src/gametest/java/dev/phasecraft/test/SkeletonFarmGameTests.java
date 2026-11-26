package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The skeleton farm (the person's choice of mob farm, 2026-11-26; layout: contraptions/skeleton_farm.txt, written by
 * tools/gen_skeleton.py; examples station 36): a dark tower with a spawner room at its top. The button in the gallery
 * takes the room's floor away for a second (hidden phase shifters); the skeletons fall 28 blocks to the floor at the
 * tower's foot and die of it, and hidden phased hoppers under that floor pass their loot to a phased dropper that
 * fills the chest outside. Here the room is fed skeletons by hand (the examples' room has a spawner).
 */
public class SkeletonFarmGameTests {
    /** Generator (0, 0, 0), the landing floor's middle, in the layout. */
    static final BlockPos ORIGIN = new BlockPos(7, 3, 7);
    static final int ROOM = 28;
    static final BlockPos BUTTON = gen(5, ROOM + 2, 3);
    static final BlockPos CHEST = gen(5, 1, 3);
    private static ContraptionLayout layout;

    static ContraptionLayout layout() {
        if (layout == null) {
            layout = ContraptionLayout.load("skeleton_farm");
        }
        return layout;
    }

    static BlockPos gen(int x, int y, int z) {
        return ORIGIN.offset(x, y, z);
    }

    /** The room's floor blocks missing (none: the floor is whole). */
    static List<String> floorMissing(GameTestHelper helper) {
        List<String> out = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (!helper.getBlockState(gen(x, ROOM, z)).is(Blocks.DEEPSLATE_TILES)) {
                    out.add(x + "," + z);
                }
            }
        }
        return out;
    }

    static int bones(GameTestHelper helper) {
        ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(Items.BONE)) {
                n += chest.getItem(i).getCount();
            }
        }
        return n;
    }

    /** Items lying anywhere inside the tower. */
    static List<ItemEntity> loose(GameTestHelper helper) {
        AABB inside = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(gen(-3, 0, -3))),
                Vec3.atLowerCornerOf(helper.absolutePos(gen(4, ROOM + 5, 4))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, inside);
    }

    /**
     * Five skeletons in the room, each holding a bone it is sure to drop: they stand on the floor until the button is
     * pressed, then all fall and die, the floor is back a second later, and at least their five bones (and whatever
     * else they drop) reach the chest, nothing left lying at the tower's foot.
     */
    @GameTest(structure = "phasecraft-test:skeleton_farm", maxTicks = 600)
    public void theButtonDropsTheSkeletonsAndTheirLootFillsTheChest(GameTestHelper helper) {
        layout().build(helper);
        List<Skeleton> skeletons = new ArrayList<>();
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(floorMissing(helper), List.of(), "the floor's missing blocks at rest");
                    helper.assertValueEqual(bones(helper), 0, "bones in the chest at first");
                    for (int[] p : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}, {0, -3}}) {
                        Skeleton s = helper.spawnWithNoFreeWill(EntityTypes.SKELETON, gen(p[0], ROOM + 1, p[1]));
                        s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BONE));
                        s.setGuaranteedDrop(EquipmentSlot.MAINHAND);
                        skeletons.add(s);
                    }
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    for (Skeleton s : skeletons) {
                        helper.assertTrue(s.isAlive() && helper.relativeVec(s.position()).y >= gen(0, ROOM + 1, 0).getY(),
                                "a skeleton left the floor before the button");
                    }
                    helper.pressButton(BUTTON);
                })
                .thenWaitUntil(() -> helper.assertTrue(skeletons.stream().noneMatch(Skeleton::isAlive), "skeletons alive"))
                .thenWaitUntil(() -> helper.assertValueEqual(floorMissing(helper), List.of(), "the floor's missing blocks"))
                .thenWaitUntil(() -> helper.assertTrue(bones(helper) >= 5, "bones in the chest: " + bones(helper)))
                .thenWaitUntil(() -> helper.assertValueEqual(loose(helper).size(), 0, "items lying in the tower"))
                .thenSucceed();
    }
}
