package dev.phasecraft.edit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.piston.PhasePistonBlock;
import dev.phasecraft.shifter.HoldsBlocks;
import eu.pb4.common.protection.api.CommonProtection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Who placed each hidden machine: the blocks that change the visible world with no player acting (the three
 * shifters, phase pistons, and hoppers, droppers and dispensers, which do so once phased). A machine acts with its
 * owner's rights where the visible world is claimed ({@link MachineRights}).
 *
 * <ul>
 *   <li>Kept per hidden dimension, saved with it (saved data {@code phasecraft:machine_owners}), by position.</li>
 *   <li>Written when a player places a machine block in a hidden dimension: from editing mode, or standing in the
 *       hidden layer ({@code BlockItemMixin}; both go through vanilla's {@code BlockItem.place}).</li>
 *   <li>Forgotten when the block at the position becomes another block (the block-change hook,
 *       {@code ServerLevelPhaseLinkMixin}). A machine placed by a command or a structure, or from before owners
 *       were kept, has none.</li>
 *   <li>Carried along when a hidden piston moves the machine (only phase pistons can be moved: the others have
 *       block entities), {@code PistonBaseBlockMixin}. Otherwise a machine pushed across a claim's border from
 *       outside would arrive with no owner, and count as built inside. For the same reason a machine with no owner
 *       that a piston moves from an unprotected spot onto a protected one gets
 *       {@link CommonProtection#UNKNOWN} as its owner: nobody, whom claims refuse.</li>
 * </ul>
 */
public final class MachineOwners extends SavedData {
    private record Entry(BlockPos pos, NameAndId owner) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                NameAndId.CODEC.fieldOf("owner").forGetter(Entry::owner)).apply(i, Entry::new));
    }

    private static final Codec<MachineOwners> CODEC = Entry.CODEC.listOf().fieldOf("machines")
            .xmap(MachineOwners::new, MachineOwners::entries).codec();
    private static final SavedDataType<MachineOwners> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("phasecraft", "machine_owners"), MachineOwners::new, CODEC, null);
    private static final Map<ResourceKey<Level>, MachineOwners> LOADED = new HashMap<>();

    private final Long2ObjectOpenHashMap<NameAndId> owners = new Long2ObjectOpenHashMap<>();

    private MachineOwners() {}

    private MachineOwners(List<Entry> entries) {
        for (Entry entry : entries) {
            owners.put(entry.pos().asLong(), entry.owner());
        }
    }

    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(owners.size());
        owners.forEach((pos, owner) -> entries.add(new Entry(BlockPos.of(pos), owner)));
        return entries;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
    }

    private static MachineOwners of(ServerLevel hidden) {
        MachineOwners owners = LOADED.get(hidden.dimension());
        if (owners == null) {
            owners = hidden.getDataStorage().computeIfAbsent(TYPE);
            LOADED.put(hidden.dimension(), owners);
        }
        return owners;
    }

    /** Whether a block is one that changes the visible world on its own, and so gets an owner. */
    public static boolean isMachine(BlockState state) {
        Block block = state.getBlock();
        return HoldsBlocks.isShifter(block) || block instanceof PhasePistonBlock || block instanceof HopperBlock
                || block instanceof DispenserBlock; // droppers are dispensers
    }

    /** Who placed the machine at this spot of a hidden level, if known. */
    public static @Nullable NameAndId owner(ServerLevel hidden, BlockPos pos) {
        return of(hidden).owners.get(pos.asLong());
    }

    /** Sets (or, with null, forgets) the owner of the machine at a spot; for commands, moves and tests. */
    public static void setOwner(ServerLevel hidden, BlockPos pos, @Nullable NameAndId owner) {
        MachineOwners owners = of(hidden);
        if (owner == null ? owners.owners.remove(pos.asLong()) != null : !owner.equals(owners.owners.put(pos.asLong(), owner))) {
            owners.setDirty();
        }
    }

    /** A player placed a block: if it is a machine in a hidden level, it is theirs. */
    public static void onPlaced(ServerLevel level, BlockPos pos, NameAndId placer) {
        if (HiddenDimensions.isHidden(level.dimension()) && isMachine(level.getBlockState(pos))) {
            setOwner(level, pos, placer);
        }
    }

    /**
     * Every block change: the owner of a spot is forgotten when its block becomes another one. Not when only its
     * state changes (a shifter powered), nor when a moved machine lands (the moving block becomes the machine: its
     * owner came with it, see {@link #onMoved}).
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState.getBlock() == newState.getBlock() || !HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        MachineOwners owners = of(level);
        if (owners.owners.isEmpty() || (oldState.is(Blocks.MOVING_PISTON) && isMachine(newState))) {
            return;
        }
        if (owners.owners.remove(pos.asLong()) != null) {
            owners.setDirty();
        }
    }

    /** A piston starts moving a block from one spot to the next: a machine's owner goes with it. */
    public static void onMoved(Level level, BlockPos from, BlockPos to, BlockState moved) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension()) || !isMachine(moved)) {
            return;
        }
        NameAndId owner = owner(hidden, from);
        if (owner == null && MachineRights.anyProtection()) {
            ServerLevel visible = HiddenDimensions.visibleOf(hidden.dimension()).map(hidden.getServer()::getLevel).orElse(null);
            if (visible != null && CommonProtection.isProtected(visible, to) && !CommonProtection.isProtected(visible, from)) {
                owner = CommonProtection.UNKNOWN;
            }
        }
        setOwner(hidden, from, null);
        setOwner(hidden, to, owner);
    }
}
