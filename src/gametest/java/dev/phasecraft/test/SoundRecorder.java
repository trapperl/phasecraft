package dev.phasecraft.test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Every sound played on the server during tests (recorded by SoundRecorderMixin), for tests about where sounds are heard. */
public final class SoundRecorder {
    public record Played(ResourceKey<Level> dimension, Vec3 pos, String sound) {}

    private static final List<Played> PLAYED = new ArrayList<>();

    private SoundRecorder() {}

    public static synchronized void record(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound) {
        PLAYED.add(new Played(level.dimension(), new Vec3(x, y, z), sound.value().location().toString()));
    }

    public static synchronized void recordEvent(ServerLevel level, BlockPos pos, int type) {
        PLAYED.add(new Played(level.dimension(), Vec3.atCenterOf(pos), "level_event:" + type));
    }

    /** The sounds played so far in this dimension inside the box. */
    public static synchronized List<Played> in(ResourceKey<Level> dimension, AABB box) {
        return PLAYED.stream().filter(p -> p.dimension().equals(dimension) && box.contains(p.pos())).toList();
    }
}
