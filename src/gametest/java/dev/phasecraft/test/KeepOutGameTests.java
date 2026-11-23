package dev.phasecraft.test;

import com.mojang.authlib.GameProfile;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/** Nobody ends up in the hidden layer by accident (HiddenLayerGuard and its mixins). */
public class KeepOutGameTests {
    /**
     * A connected test player whose game mode can really change.
     * (GameTestHelper's makeMockServerPlayerInLevel always reports Creative.)
     */
    private static ServerPlayer join(GameTestHelper helper, GameType mode) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "keep-out-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        return player;
    }

    private static void leave(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    private static void moveTo(ServerPlayer player, ServerLevel level, Vec3 spot) {
        player.teleport(new TeleportTransition(level, spot, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
    }

    /** A player who switches to Survival inside the hidden layer is put back in the visible layer, same spot. */
    @GameTest(maxTicks = 60)
    public void survivalPlayerIsMovedOutOfTheHiddenLayer(GameTestHelper helper) {
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2)));
        ServerPlayer player = join(helper, GameType.CREATIVE);
        moveTo(player, layer.level(), spot);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> helper.assertValueEqual(player.level(), layer.level(), "creative player should stay"))
                .thenExecute(() -> player.setGameMode(GameType.SURVIVAL))
                .thenWaitUntil(() -> helper.assertValueEqual(player.level(), helper.getLevel(), "level after switching to survival"))
                .thenExecute(() -> {
                    helper.assertTrue(player.position().distanceTo(spot) < 0.01, "moved somewhere else: " + player.position());
                    leave(helper, player);
                })
                .thenSucceed();
    }

    /** Creative and Spectator players may stay in the hidden layer. */
    @GameTest(maxTicks = 60)
    public void creativeAndSpectatorPlayersMayStay(GameTestHelper helper) {
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        ServerPlayer player = join(helper, GameType.CREATIVE);
        moveTo(player, layer.level(), Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))));
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> helper.assertValueEqual(player.level(), layer.level(), "creative player's level"))
                .thenExecute(() -> player.setGameMode(GameType.SPECTATOR))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(player.level(), layer.level(), "spectator's level");
                    leave(helper, player);
                })
                .thenSucceed();
    }

    /** Moved out where visible blocks are: lifted to the first spot above where the player fits. */
    @GameTest(maxTicks = 60)
    public void movedOutPlayersAreLiftedOutOfVisibleBlocks(GameTestHelper helper) {
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        BlockPos feet = new BlockPos(4, 2, 4);
        helper.setBlock(feet, Blocks.STONE);
        helper.setBlock(feet.above(), Blocks.STONE);
        Vec3 spot = Vec3.atBottomCenterOf(helper.absolutePos(feet));
        ServerPlayer player = join(helper, GameType.CREATIVE);
        moveTo(player, layer.level(), spot);
        helper.startSequence()
                .thenExecute(() -> player.setGameMode(GameType.SURVIVAL))
                .thenWaitUntil(() -> helper.assertValueEqual(player.level(), helper.getLevel(), "level after switching to survival"))
                .thenExecute(() -> {
                    helper.assertValueEqual(player.blockPosition(), helper.absolutePos(feet.above(2)), "lifted position");
                    leave(helper, player);
                })
                .thenSucceed();
    }

    /** A respawn point (bed, anchor) cannot be set in the hidden layer; in the visible world it still can. */
    @GameTest
    public void noRespawnPointInTheHiddenLayer(GameTestHelper helper) {
        ServerPlayer player = join(helper, GameType.CREATIVE);
        try {
            BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    new LevelData.RespawnData(GlobalPos.of(HiddenDimensions.HIDDEN_OVERWORLD, at), 0, 0), true), false);
            helper.assertTrue(player.getRespawnConfig() == null, "respawn point set in the hidden layer: " + player.getRespawnConfig());
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(
                    new LevelData.RespawnData(GlobalPos.of(Level.OVERWORLD, at), 0, 0), true), false);
            helper.assertTrue(player.getRespawnConfig() != null, "visible respawn point refused");
        } finally {
            leave(helper, player);
        }
        helper.succeed();
    }

    /** A Nether portal in the hidden layer does nothing: a pig standing in it stays put. */
    @GameTest(maxTicks = 120)
    public void portalsInTheHiddenLayerDoNothing(GameTestHelper helper) {
        HiddenLayer layer = HiddenLayer.of(helper.getLevel());
        ServerLevel hidden = layer.level();
        BlockPos corner = helper.absolutePos(new BlockPos(1, 1, 5));
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                boolean frame = x == 0 || x == 3 || y == 0 || y == 4;
                if (frame) {
                    layer.setBlock(corner.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        for (int x = 1; x < 3; x++) {
            for (int y = 1; y < 4; y++) {
                layer.setBlock(corner.offset(x, y, 0), Blocks.NETHER_PORTAL.defaultBlockState()
                        .setValue(NetherPortalBlock.AXIS, Direction.Axis.X));
            }
        }
        Pig pig = EntityTypes.PIG.create(hidden, EntitySpawnReason.COMMAND);
        pig.setNoAi(true);
        pig.snapTo(Vec3.atBottomCenterOf(corner.offset(1, 1, 0)));
        hidden.addFreshEntity(pig);

        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertTrue(layer.getBlock(corner.offset(1, 1, 0)).is(Blocks.NETHER_PORTAL), "test portal broke");
                    helper.assertTrue(!pig.isRemoved() && pig.level() == hidden, "pig went through the hidden portal to " + pig.level().dimension());
                    pig.discard();
                    for (int x = 0; x < 4; x++) {
                        for (int y = 0; y < 5; y++) {
                            layer.removeBlock(corner.offset(x, y, 0));
                        }
                    }
                })
                .thenSucceed();
    }
}
