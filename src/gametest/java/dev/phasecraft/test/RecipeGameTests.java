package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Survival recipes (the person's starting point for balancing, 2026-11-23):
 * a Phase Block is cheap (four from one amethyst shard, four redstone and four
 * cobblestone); ports and phase pistons are a vanilla part plus a Phase Block.
 */
public class RecipeGameTests {
    private static ItemStack craft(GameTestHelper helper, int width, int height, Item... grid) {
        List<ItemStack> items = new ArrayList<>();
        for (Item item : grid) {
            items.add(item == null ? ItemStack.EMPTY : new ItemStack(item));
        }
        CraftingInput input = CraftingInput.of(width, height, items);
        return helper.getLevel().getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .map(holder -> holder.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    private static void assertMakes(GameTestHelper helper, ItemStack made, Item item, int count, String what) {
        helper.assertTrue(made.is(item) && made.getCount() == count, what + ": got " + made + ", expected " + count + " " + item);
    }

    @GameTest(maxTicks = 20)
    public void phaseCraftRecipes(GameTestHelper helper) {
        Item c = Items.COBBLESTONE;
        Item r = Items.REDSTONE;
        Item a = Items.AMETHYST_SHARD;
        Item phase = PhaseCraftBlocks.PHASE_BLOCK_ITEM;
        assertMakes(helper, craft(helper, 3, 3, c, r, c, r, a, r, c, r, c), phase, 4, "phase block");
        assertMakes(helper, craft(helper, 2, 1, phase, Items.REDSTONE_TORCH), PhaseCraftBlocks.PHASE_PORT_ITEM, 1, "phase port");
        assertMakes(helper, craft(helper, 2, 1, Items.PISTON, phase), PhaseCraftBlocks.PHASE_PISTON_ITEM, 1, "phase piston");
        assertMakes(helper, craft(helper, 2, 1, PhaseCraftBlocks.PHASE_PISTON_ITEM, Items.SLIME_BALL),
                PhaseCraftBlocks.STICKY_PHASE_PISTON_ITEM, 1, "sticky phase piston");
        assertMakes(helper, craft(helper, 2, 1, Items.STICKY_PISTON, phase),
                PhaseCraftBlocks.STICKY_PHASE_PISTON_ITEM, 1, "sticky phase piston from a sticky piston");
        // The person, 2026-11-23: ender pearls, "since this would be pretty powerful"; two per craft.
        assertMakes(helper, craft(helper, 3, 1, phase, Items.ENDER_PEARL, Items.PISTON),
                PhaseCraftBlocks.PHASE_SHIFTER_ITEM, 2, "phase shifter");
        helper.succeed();
    }
}
