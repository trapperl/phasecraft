package dev.phasecraft.shifter;

import dev.phasecraft.PhaseCraft;
import dev.phasecraft.layer.HiddenDimensions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The area shifter (the person's idea, 2026-11-25: two corners make a box "and then you could turn the whole cube
 * off and on"; "It does feel like cheating though. We could keep it as an option"), placed in the hidden layer.
 * Two of them at opposite corners of a box (the corners' own spots included) take everything in the box out of the
 * visible world while both are powered, layer by layer from the top, and put it back, layer by layer from the
 * bottom, when either loses its power.
 *
 * <ul>
 *   <li>An option: it does nothing unless the game rule {@code phasecraft:area_shifters} is on (off by
 *       default).</li>
 *   <li>Pairs: each corner pairs with its nearest other area shifter at most {@link #CAP} - 1 blocks away on every
 *       axis (a box of up to CAP x CAP x CAP); two corners pair only if each is the other's nearest, and not if two
 *       are equally near. Keep boxes apart.</li>
 *   <li>Each block is taken and put back as a phase shifter does it ({@link Shifting}): what a piston could push,
 *       blocks with contents too; air, fragile blocks (torches...) and immovable ones (obsidian...) stay.</li>
 *   <li>Speed: a layer every 1 to 4 redstone ticks, or all at once at 0 (its delay, set by clicking it; the
 *       leader's counts). A layer with nothing to take costs no time. Turned around mid-move it goes the other
 *       way; putting back waits while a mob stands in a spot, and the layers above wait for it.</li>
 *   <li>The leader (the corner whose position sorts first) holds the blocks; removing it puts them all back at
 *       once, or drops what cannot go back; removing the other corner makes the leader put them back.</li>
 *   <li>Passes no power on; silent in the visible world; placed in the visible layer it does nothing.</li>
 * </ul>
 */
public class AreaShifterBlock extends Block implements EntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final IntegerProperty DELAY = IntegerProperty.create("delay", 0, 4);
    /** The longest side of a box. */
    public static final int CAP = 16;
    /** Off by default: a box of the world switched off and on is more than vanilla lets a piston do. */
    public static final GameRule<Boolean> RULE = GameRuleBuilder.forBoolean(false)
            .category(GameRuleCategory.MISC)
            .buildAndRegister(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "area_shifters"));

    public AreaShifterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false).setValue(DELAY, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, DELAY);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AreaShifterBlockEntity(pos, state);
    }

    /** Clicking it sets its delay: 1 to 4 redstone ticks a layer, or 0, all at once. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        level.setBlockAndUpdate(pos, state.cycle(DELAY));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(this)) {
            update(level, pos, state);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            @Nullable Orientation orientation, boolean movedByPiston) {
        update(level, pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof AreaShifterBlockEntity entity) {
            step(level, pos, entity);
        }
    }

    /** Follows this corner's power, and tells the pair's leader. */
    private void update(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel hidden) || !HiddenDimensions.isHidden(hidden.dimension())
                || !(hidden.getBlockEntity(pos) instanceof AreaShifterBlockEntity entity)) {
            return;
        }
        boolean powered = hidden.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }
        hidden.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        BlockPos leaderPos = entity.partner() != null ? pos : entity.leader();
        if (leaderPos == null || !(hidden.getBlockEntity(leaderPos) instanceof AreaShifterBlockEntity)) {
            BlockPos partner = partner(hidden, pos);
            if (partner == null) {
                return;
            }
            leaderPos = pos.compareTo(partner) < 0 ? pos : partner;
        }
        if (hidden.getBlockEntity(leaderPos) instanceof AreaShifterBlockEntity leader) {
            react(hidden, leaderPos, leader);
        }
    }

    /** The leader hears of a change: starts a move if both corners are now powered, and moves its next layer. */
    private void react(ServerLevel hidden, BlockPos pos, AreaShifterBlockEntity leader) {
        boolean wasTaking = leader.cursor() != Integer.MIN_VALUE;
        if (leader.partner() == null) {
            BlockPos partner = partner(hidden, pos);
            if (partner == null || pos.compareTo(partner) > 0 || !powered(hidden, pos) || !powered(hidden, partner)
                    || !hidden.getGameRules().get(RULE)) {
                return;
            }
            leader.setPartner(partner);
            if (hidden.getBlockEntity(partner) instanceof AreaShifterBlockEntity other) {
                other.setLeader(pos);
            }
        }
        if (active(hidden, pos, leader) && !wasTaking) {
            leader.setCursor(Math.max(pos.getY(), leader.partner().getY()));
        }
        step(hidden, pos, leader);
    }

    private static boolean powered(ServerLevel hidden, BlockPos pos) {
        BlockState state = hidden.getBlockState(pos);
        return state.getBlock() instanceof AreaShifterBlock && state.getValue(POWERED);
    }

    /** Whether the leader's box should be out: the rule on, both corners there and powered. */
    private static boolean active(ServerLevel hidden, BlockPos pos, AreaShifterBlockEntity leader) {
        return leader.partner() != null && hidden.getGameRules().get(RULE) && powered(hidden, pos)
                && powered(hidden, leader.partner());
    }

    /**
     * The corner this one pairs with: its nearest other area shifter at most CAP - 1 blocks away on every axis, if
     * that one's nearest is this one; null if there is none, or a tie.
     */
    static @Nullable BlockPos partner(ServerLevel hidden, BlockPos pos) {
        BlockPos near = nearest(hidden, pos);
        return near != null && pos.equals(nearest(hidden, near)) ? near : null;
    }

    private static @Nullable BlockPos nearest(ServerLevel hidden, BlockPos pos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean tie = false;
        int r = CAP - 1;
        for (BlockPos at : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (at.equals(pos) || !hidden.isLoaded(at) || !(hidden.getBlockState(at).getBlock() instanceof AreaShifterBlock)) {
                continue;
            }
            double distance = at.distSqr(pos);
            if (distance < bestDistance) {
                best = at.immutable();
                bestDistance = distance;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** Moves the leader's next layer, if it is its time, and books the step after. */
    private void step(ServerLevel hidden, BlockPos pos, AreaShifterBlockEntity leader) {
        ServerLevel visible = Shifting.visibleOf(hidden);
        BlockPos partner = leader.partner();
        if (visible == null || partner == null) {
            return;
        }
        BlockPos min = new BlockPos(Math.min(pos.getX(), partner.getX()), Math.min(pos.getY(), partner.getY()),
                Math.min(pos.getZ(), partner.getZ()));
        BlockPos max = new BlockPos(Math.max(pos.getX(), partner.getX()), Math.max(pos.getY(), partner.getY()),
                Math.max(pos.getZ(), partner.getZ()));
        int delay = hidden.getBlockState(pos).getValue(DELAY);
        boolean taking = active(hidden, pos, leader);
        if (!taking) {
            leader.setCursor(Integer.MIN_VALUE);
        }
        long time = hidden.getGameTime();
        if (leader.nextStep() > time + 8) {
            leader.setNextStep(time);
        }
        if (time < leader.nextStep()) {
            hidden.scheduleTick(pos, this, (int) (leader.nextStep() - time));
            return;
        }
        int layers = CAP + 1;                                  // at most one box's worth of layers in one tick
        do {
            if (taking) {
                if (!takeLayer(visible, min, max, leader)) {
                    return;
                }
            } else {
                int left = putBackLayer(visible, leader);
                if (left < 0) {
                    leader.setPartner(null);
                    if (hidden.getBlockEntity(partner) instanceof AreaShifterBlockEntity other) {
                        other.setLeader(null);
                    }
                    return;
                }
                if (left > 0) {
                    hidden.scheduleTick(pos, this, 1);           // a spot is taken: try again next tick
                    return;
                }
                if (leader.holding() == 0) {
                    leader.setNextStep(time + 2L * delay);      // the rhythm holds for a move that comes next
                    leader.setPartner(null);
                    if (hidden.getBlockEntity(partner) instanceof AreaShifterBlockEntity other) {
                        other.setLeader(null);
                    }
                    return;
                }
            }
        } while (delay == 0 && --layers > 0);
        leader.setNextStep(time + 2L * delay);
        hidden.scheduleTick(pos, this, 2 * delay);
    }

    /**
     * Takes the highest layer at or below the cursor that has something to take; false if there was none (the move
     * is done).
     */
    private static boolean takeLayer(ServerLevel visible, BlockPos min, BlockPos max, AreaShifterBlockEntity leader) {
        for (int y = Math.min(leader.cursor(), max.getY()); y >= min.getY(); y--) {
            List<BlockPos> layer = new ArrayList<>();
            for (BlockPos at : BlockPos.betweenClosed(new BlockPos(min.getX(), y, min.getZ()), new BlockPos(max.getX(), y, max.getZ()))) {
                if (leader.held(at) == null && visible.isLoaded(at)
                        && Shifting.reaction(visible, at, visible.getBlockState(at)) == Shifting.Reaction.TAKE) {
                    layer.add(at.immutable());
                }
            }
            if (layer.isEmpty()) {
                continue;
            }
            for (BlockPos at : layer) {
                Shifting.Held held = Shifting.take(visible, at);
                if (held != null) {
                    leader.hold(at, held);
                }
            }
            leader.setCursor(y - 1);
            return true;
        }
        leader.setCursor(min.getY() - 1);
        return false;
    }

    /** Puts back the lowest layer it holds: how many of that layer could not go back yet, or -1 if none is held. */
    private static int putBackLayer(ServerLevel visible, AreaShifterBlockEntity leader) {
        Map<BlockPos, Shifting.Held> held = leader.heldMap();
        if (held.isEmpty()) {
            return -1;
        }
        int y = held.keySet().iterator().next().getY();      // ordered by y first
        int left = 0;
        for (BlockPos at : new ArrayList<>(held.keySet())) {
            if (at.getY() != y) {
                break;
            }
            if (Shifting.putBack(visible, at, held.get(at))) {
                leader.hold(at, null);
            } else {
                left++;
            }
        }
        return left;
    }

    /**
     * A corner is being removed. The leader puts everything back at once, or drops what cannot go back; the other
     * corner tells its leader, which then puts the box back layer by layer.
     */
    static void removed(AreaShifterBlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel hidden)) {
            return;
        }
        if (entity.partner() != null) {
            ServerLevel visible = Shifting.visibleOf(hidden);
            for (Map.Entry<BlockPos, Shifting.Held> e : new ArrayList<>(entity.heldMap().entrySet())) {
                entity.hold(e.getKey(), null);
                if (visible != null) {
                    Shifting.putBackOrDrop(visible, e.getKey(), e.getValue());
                }
            }
            if (hidden.getBlockEntity(entity.partner()) instanceof AreaShifterBlockEntity other) {
                other.setLeader(null);
            }
            entity.setPartner(null);
        } else if (entity.leader() != null && hidden.getBlockState(entity.leader()).getBlock() instanceof AreaShifterBlock block) {
            hidden.scheduleTick(entity.leader(), block, 1);
        }
    }
}
