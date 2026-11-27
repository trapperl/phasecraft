package dev.phasecraft.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One thing to measure, in several variants (with PhaseCraft's part, without it, or the nearest vanilla thing).
 * Every variant is built from nothing in its own fresh area, runs for a while to settle, and is then measured.
 */
final class Scenario {
    /**
     * @param switches sets {@link Switches} (they are all on before it runs), before any chunk is loaded
     * @param build    builds the variant once its chunks tick
     * @param stimulus runs at the start of every measured tick (tick number from 0), inside the measured time
     */
    record Variant(String name, Runnable switches, Consumer<Ctx> build, BiConsumer<Ctx, Integer> stimulus) {}

    final String name;
    final String what;
    /** Chunks kept loaded and ticking around the origin: (2 rx + 1) x (2 rz + 1). */
    int rx = 3;
    int rz = 3;
    int settle = 60;
    /** Measured ticks; 0: the run's default (-Pperf.ticks). */
    int measure;
    int cooldown = 30;
    /** Also report the heap in use after a collection, before measuring. */
    boolean heap;
    final List<Variant> variants = new ArrayList<>();

    Scenario(String name, String what) {
        this.name = name;
        this.what = what;
    }

    Scenario chunks(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
        return this;
    }

    Scenario variant(String name, Runnable switches, Consumer<Ctx> build) {
        return variant(name, switches, build, (ctx, tick) -> { });
    }

    Scenario variant(String name, Runnable switches, Consumer<Ctx> build, BiConsumer<Ctx, Integer> stimulus) {
        variants.add(new Variant(name, switches, build, stimulus));
        return this;
    }
}
