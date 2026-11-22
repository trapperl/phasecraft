package dev.phasecraft;

import dev.phasecraft.link.PhasePortBlock;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** PhaseCraft's blocks and their items. */
public final class PhaseCraftBlocks {
    private static final Identifier PHASE_PORT_ID = Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "phase_port");

    public static final Block PHASE_PORT = Registry.register(BuiltInRegistries.BLOCK, PHASE_PORT_ID,
            new PhasePortBlock(BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, PHASE_PORT_ID))
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(0.5F)
                    .sound(SoundType.AMETHYST)));

    public static final Item PHASE_PORT_ITEM = Registry.register(BuiltInRegistries.ITEM, PHASE_PORT_ID,
            new BlockItem(PHASE_PORT, new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, PHASE_PORT_ID))
                    .useBlockDescriptionPrefix()));

    private PhaseCraftBlocks() {}

    /** Loads the class (registering the blocks) and adds the items to the Redstone creative tab. */
    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS)
                .register(output -> output.accept(PHASE_PORT_ITEM));
    }
}
