package dev.phasecraft;

import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import dev.phasecraft.shifter.PhaseShifterBlock;
import dev.phasecraft.shifter.PhaseShifterBlockEntity;
import java.util.Set;
import net.minecraft.world.level.block.entity.BlockEntityType;

import dev.phasecraft.link.PhasePortBlock;
import dev.phasecraft.phased.PhaseBlock;
import dev.phasecraft.piston.PhasePistonBlock;
import java.util.function.Function;
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
import net.minecraft.world.level.material.PushReaction;

/** PhaseCraft's blocks and their items. */
public final class PhaseCraftBlocks {
    public static final Block PHASE_PORT = block("phase_port", PhasePortBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(0.5F).sound(SoundType.AMETHYST));
    public static final Item PHASE_PORT_ITEM = item("phase_port", PHASE_PORT);

    public static final Block PHASE_PISTON = block("phase_piston", properties -> new PhasePistonBlock(false, properties),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5F).isRedstoneConductor((s, l, p) -> false));
    public static final Item PHASE_PISTON_ITEM = item("phase_piston", PHASE_PISTON);

    public static final Block STICKY_PHASE_PISTON = block("sticky_phase_piston", properties -> new PhasePistonBlock(true, properties),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5F).isRedstoneConductor((s, l, p) -> false));
    public static final Item STICKY_PHASE_PISTON_ITEM = item("sticky_phase_piston", STICKY_PHASE_PISTON);

    public static final Block PHASE_BLOCK = block("phase_block", PhaseBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5F).sound(SoundType.AMETHYST)
                    .pushReaction(PushReaction.IMMOVEABLE));
    public static final Item PHASE_BLOCK_ITEM = item("phase_block", PHASE_BLOCK);

    /** Passes no power on, as a piston does not: powering one shifter powers no other (the person, 2026-11-25). */
    public static final Block PHASE_SHIFTER = block("phase_shifter", PhaseShifterBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5F).sound(SoundType.AMETHYST)
                    .pushReaction(PushReaction.IMMOVEABLE).isRedstoneConductor((s, l, p) -> false));
    public static final Item PHASE_SHIFTER_ITEM = item("phase_shifter", PHASE_SHIFTER);
    public static final BlockEntityType<PhaseShifterBlockEntity> PHASE_SHIFTER_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, id("phase_shifter"),
            new BlockEntityType<>(PhaseShifterBlockEntity::new, Set.of(PHASE_SHIFTER)));

    /** A phase shifter for the line of blocks in front of it, as a piston pushes (the person, 2026-11-25). */
    public static final Block LINE_SHIFTER = block("line_shifter", LineShifterBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5F).sound(SoundType.AMETHYST)
                    .pushReaction(PushReaction.IMMOVEABLE).isRedstoneConductor((s, l, p) -> false));
    public static final Item LINE_SHIFTER_ITEM = item("line_shifter", LINE_SHIFTER);
    public static final BlockEntityType<LineShifterBlockEntity> LINE_SHIFTER_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, id("line_shifter"),
            new BlockEntityType<>(LineShifterBlockEntity::new, Set.of(LINE_SHIFTER)));

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, path);
    }

    private static Block block(String path, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
        return Registry.register(BuiltInRegistries.BLOCK, id(path),
                factory.apply(properties.setId(ResourceKey.create(Registries.BLOCK, id(path)))));
    }

    private static Item item(String path, Block block) {
        return Registry.register(BuiltInRegistries.ITEM, id(path), new BlockItem(block, new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id(path)))
                .useBlockDescriptionPrefix()));
    }

    private PhaseCraftBlocks() {}

    /** Loads the class (registering the blocks) and adds the items to the Redstone creative tab. */
    public static void register() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS)
                .register(output -> {
                    output.accept(PHASE_PORT_ITEM);
                    output.accept(PHASE_PISTON_ITEM);
                    output.accept(STICKY_PHASE_PISTON_ITEM);
                    output.accept(PHASE_BLOCK_ITEM);
                    output.accept(PHASE_SHIFTER_ITEM);
                    output.accept(LINE_SHIFTER_ITEM);
                });
    }
}
