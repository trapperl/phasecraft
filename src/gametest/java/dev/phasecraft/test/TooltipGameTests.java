package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Every PhaseCraft item says in its tooltip what it does and that it works in the hidden layer (a Survival player has no
 * other way to find out in the game), and every game rule has a name for the Edit Game Rules screen.
 */
public class TooltipGameTests {
    private static List<Component> tooltip(Item item) {
        List<Component> lines = new ArrayList<>();
        item.appendHoverText(new ItemStack(item), Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add, TooltipFlag.NORMAL);
        return lines;
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents t ? t.getKey() : line.getString();
    }

    @GameTest(maxTicks = 20)
    public void everyItemSaysWhatItDoes(GameTestHelper helper) {
        Language language = Language.getInstance();
        int items = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("phasecraft")) {
                continue;
            }
            items++;
            List<Component> lines = tooltip(item);
            helper.assertTrue(lines.size() >= 2, item + ": tooltip " + lines.stream().map(TooltipGameTests::key).toList()
                    + ", expected what it does and the hidden-layer line");
            helper.assertTrue(key(lines.getLast()).equals(PhaseCraftBlocks.TOOLTIP_HIDDEN), item + ": last line " + key(lines.getLast()));
            for (Component line : lines) {
                helper.assertTrue(language.has(key(line)), item + ": no translation for " + key(line));
            }
        }
        helper.assertTrue(items == 7, "PhaseCraft items: " + items + ", expected 7");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void everyGameRuleIsNamed(GameTestHelper helper) {
        Language language = Language.getInstance();
        for (String rule : List.of("survival_access", "area_shifters")) {
            String key = "gamerule.phasecraft." + rule;
            helper.assertTrue(language.has(key), "no name for the game rule phasecraft:" + rule + " (" + key + ")");
        }
        helper.succeed();
    }
}
