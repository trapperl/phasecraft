package dev.phasecraft.perf;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.link.HiddenChunks;
import dev.phasecraft.link.PhaseLink;
import dev.phasecraft.shifter.AreaShifterBlock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.Recording;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the scenarios one measurement after another on the server thread and records each tick: wall time from the
 * start-of-tick event to after every end-of-tick handler (PhaseCraft's included), the server thread's CPU time and
 * what it allocated. Results: results.jsonl (one line a measurement) and results.md (tables) in build/perf.
 *
 * <p>Order: for each scenario, for each round, each variant; so variants of a scenario are measured next to each
 * other in time, and each round again, to see how much the machine's noise moves the numbers.</p>
 */
final class Runner {
    private static final Logger LOGGER = LoggerFactory.getLogger("phasecraft-perf");
    private static final Identifier EARLY = Identifier.fromNamespaceAndPath("phasecraft-perf", "early");
    private static final Identifier LATE = Identifier.fromNamespaceAndPath("phasecraft-perf", "late");
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static final int ROUNDS = Integer.getInteger("phasecraft.perf.rounds", 3);
    private static final int TICKS = Integer.getInteger("phasecraft.perf.ticks", 300);
    private static final float RATE = Float.parseFloat(System.getProperty("phasecraft.perf.rate", "20"));
    private static final boolean JFR = Boolean.getBoolean("phasecraft.perf.jfr");
    private static final Path OUT = Path.of(System.getProperty("phasecraft.perf.out", "perf"));

    private enum Phase { START, WAIT, SETTLE, MEASURE, COOLDOWN }

    private record Job(Scenario scenario, Scenario.Variant variant, int round) {}

    /** One measurement's numbers. */
    private record Result(Job job, Map<String, Double> values) {}

    private static final List<Job> JOBS = new ArrayList<>();
    private static final List<Result> RESULTS = new ArrayList<>();
    private static int jobIndex = -1;
    private static Phase phase;
    private static int count;
    private static Ctx ctx;
    private static List<ChunkPos> forced;

    private static boolean sampling;
    private static long wallStart;
    private static long cpuStart;
    private static long allocStart;
    private static long[] wall;
    private static long[] cpu;
    private static long[] alloc;
    private static long processCpuStart;
    private static long gcCountStart;
    private static long gcMsStart;
    private static long visibleChangesStart;
    private static long hiddenChangesStart;
    private static long queuedStart;
    private static long portsStart;
    private static double heapMb;
    private static Recording recording;

    private Runner() {}

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(Runner::started);
        Event<ServerTickEvents.StartTick> start = ServerTickEvents.START_SERVER_TICK;
        start.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        start.register(EARLY, Runner::startTick);
        Event<ServerTickEvents.EndTick> end = ServerTickEvents.END_SERVER_TICK;
        end.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
        end.register(LATE, Runner::endTick);
    }

    private static void started(MinecraftServer server) {
        String only = System.getProperty("phasecraft.perf.scenarios", "");
        List<String> wanted = only.isBlank() ? List.of() : Arrays.asList(only.split(","));
        String onlyVariants = System.getProperty("phasecraft.perf.variants", "");
        List<String> wantedVariants = onlyVariants.isBlank() ? List.of() : Arrays.asList(onlyVariants.split(","));
        for (Scenario scenario : Scenarios.all()) {
            if (!wanted.isEmpty() && wanted.stream().noneMatch(scenario.name::contains)) {
                continue;
            }
            for (int round = 1; round <= ROUNDS; round++) {
                for (Scenario.Variant variant : scenario.variants) {
                    if (wantedVariants.isEmpty() || wantedVariants.contains(variant.name())) {
                        JOBS.add(new Job(scenario, variant, round));
                    }
                }
            }
        }
        GameRules rules = server.getGameRules();
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(AreaShifterBlock.RULE, true, server);
        server.tickRateManager().setTickRate(RATE);
        try {
            Files.createDirectories(OUT);
            Files.deleteIfExists(OUT.resolve("results.jsonl"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOGGER.info("{} measurements: {} rounds, {} ticks each, {} ticks a second, JFR {}", JOBS.size(), ROUNDS, TICKS, RATE, JFR);
        next(server);
    }

    private static void next(MinecraftServer server) {
        jobIndex++;
        if (jobIndex >= JOBS.size()) {
            phase = null;
            writeSummary();
            LOGGER.info("done: {}", OUT.resolve("results.md").toAbsolutePath());
            server.halt(false);
            return;
        }
        phase = Phase.START;
    }

    private static int measureTicks(Scenario scenario) {
        return scenario.measure > 0 ? scenario.measure : TICKS;
    }

    private static void startTick(MinecraftServer server) {
        if (phase != Phase.MEASURE) {
            return;
        }
        sampling = true;
        allocStart = THREADS.getCurrentThreadAllocatedBytes();
        cpuStart = THREADS.getCurrentThreadCpuTime();
        wallStart = System.nanoTime();
        JOBS.get(jobIndex).variant().stimulus().accept(ctx, count);
    }

    private static void endTick(MinecraftServer server) {
        if (phase == null) {
            return;
        }
        Job job = JOBS.get(jobIndex);
        Scenario scenario = job.scenario();
        if (sampling) {
            wall[count] = System.nanoTime() - wallStart;
            cpu[count] = THREADS.getCurrentThreadCpuTime() - cpuStart;
            alloc[count] = THREADS.getCurrentThreadAllocatedBytes() - allocStart;
            sampling = false;
        }
        if (ctx != null) {
            for (FakePlayers.Fake fake : ctx.players) {
                fake.drain(ctx);
            }
        }
        switch (phase) {
            case START -> {
                Switches.reset();
                job.variant().switches().run();
                ServerLevel visible = server.overworld();
                ServerLevel hidden = server.getLevel(HiddenDimensions.HIDDEN_OVERWORLD);
                // Areas 2048 blocks apart along x, away from the world spawn.
                ctx = new Ctx(server, visible, hidden, new BlockPos(8192 + 2048 * jobIndex, 64, 8192));
                forced = new ArrayList<>();
                ChunkPos centre = ChunkPos.containing(ctx.origin);
                for (int x = -scenario.rx; x <= scenario.rx; x++) {
                    for (int z = -scenario.rz; z <= scenario.rz; z++) {
                        forced.add(new ChunkPos(centre.x() + x, centre.z() + z));
                    }
                }
                forced.forEach(chunk -> visible.setChunkForced(chunk.x(), chunk.z(), true));
                phase = Phase.WAIT;
                count = 0;
            }
            case WAIT -> {
                count++;
                boolean ready = forced.stream().allMatch(chunk -> ctx.visible.isPositionTickingWithEntitiesLoaded(chunk.pack())
                        && (!Switches.followTicking || ctx.hidden.isPositionTickingWithEntitiesLoaded(chunk.pack())
                                // An empty hidden chunk is never loaded for its twin (HiddenChunks).
                                || !HiddenChunks.mayHold(ctx.hidden, chunk.x(), chunk.z())));
                if (ready || count > 2400) {
                    if (!ready) {
                        LOGGER.warn("{}: chunks not all ticking after {} ticks; going on", label(job), count);
                    }
                    job.variant().build().accept(ctx);
                    phase = Phase.SETTLE;
                    count = 0;
                }
            }
            case SETTLE -> {
                count++;
                if (count >= scenario.settle) {
                    beginMeasure(job);
                }
            }
            case MEASURE -> {
                count++;
                if (count >= measureTicks(scenario)) {
                    endMeasure(job);
                    for (FakePlayers.Fake fake : ctx.players) {
                        server.getPlayerList().remove(fake.player);
                    }
                    ctx.players.clear();
                    forced.forEach(chunk -> ctx.visible.setChunkForced(chunk.x(), chunk.z(), false));
                    phase = Phase.COOLDOWN;
                    count = 0;
                }
            }
            case COOLDOWN -> {
                count++;
                if (count >= scenario.cooldown) {
                    Switches.reset();
                    ctx = null;
                    next(server);
                }
            }
        }
    }

    private static void beginMeasure(Job job) {
        int ticks = measureTicks(job.scenario());
        wall = new long[ticks];
        cpu = new long[ticks];
        alloc = new long[ticks];
        ctx.extra.clear();
        heapMb = 0;
        if (job.scenario().heap) {
            System.gc();
            System.gc();
            Runtime runtime = Runtime.getRuntime();
            heapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0;
        }
        gcCountStart = gc(true);
        gcMsStart = gc(false);
        visibleChangesStart = Switches.visibleChanges;
        hiddenChangesStart = Switches.hiddenChanges;
        queuedStart = linkStat("queued");
        portsStart = linkStat("portsRefreshed");
        if (JFR) {
            recording = JfrSummary.start();
        }
        processCpuStart = OS.getProcessCpuTime();
        phase = Phase.MEASURE;
        count = 0;
    }

    private static void endMeasure(Job job) {
        int ticks = wall.length;
        Map<String, Double> v = new LinkedHashMap<>();
        long processCpu = OS.getProcessCpuTime() - processCpuStart;
        long[] sorted = wall.clone();
        Arrays.sort(sorted);
        v.put("ticks", (double) ticks);
        v.put("meanMs", Arrays.stream(wall).average().orElse(0) / 1e6);
        v.put("p50Ms", sorted[ticks / 2] / 1e6);
        v.put("p95Ms", sorted[Math.min(ticks - 1, (int) Math.ceil(ticks * 0.95) - 1)] / 1e6);
        v.put("maxMs", sorted[ticks - 1] / 1e6);
        v.put("cpuMs", Arrays.stream(cpu).average().orElse(0) / 1e6);
        v.put("allocKb", Arrays.stream(alloc).average().orElse(0) / 1024);
        v.put("processCpuMs", processCpu / 1e6 / ticks);
        v.put("gcCount", (double) (gc(true) - gcCountStart));
        v.put("gcMs", (double) (gc(false) - gcMsStart));
        v.put("visibleChanges", (double) (Switches.visibleChanges - visibleChangesStart) / ticks);
        v.put("hiddenChanges", (double) (Switches.hiddenChanges - hiddenChangesStart) / ticks);
        v.put("linkQueued", (double) (linkStat("queued") - queuedStart) / ticks);
        v.put("portsRefreshed", (double) (linkStat("portsRefreshed") - portsStart) / ticks);
        v.put("visibleChunks", (double) fullChunks(ctx.visible, job.scenario()));
        v.put("hiddenChunks", (double) fullChunks(ctx.hidden, job.scenario()));
        if (job.scenario().heap) {
            v.put("heapMb", heapMb);
        }
        v.put("knownPlates", (double) knownPlates());
        if (!ctx.players.isEmpty()) {
            v.put("players", (double) ctx.players.stream().filter(fake -> !fake.player.hasDisconnected()).count());
        }
        ctx.extra.forEach((key, value) -> v.put(key + "PerTick", value / ticks));
        if (recording != null) {
            try {
                v.put("phasecraftShare", JfrSummary.finish(recording, OUT.resolve("jfr"),
                        job.scenario().name + "__" + job.variant().name() + "__r" + job.round()));
            } catch (IOException e) {
                LOGGER.error("JFR", e);
            }
            recording = null;
        }
        RESULTS.add(new Result(job, v));
        StringBuilder json = new StringBuilder("{\"scenario\":\"" + job.scenario().name + "\",\"variant\":\"" + job.variant().name()
                + "\",\"round\":" + job.round());
        v.forEach((key, value) -> json.append(String.format(Locale.ROOT, ",\"%s\":%.4f", key, value)));
        json.append("}\n");
        try {
            Files.writeString(OUT.resolve("results.jsonl"), json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOGGER.info("[{}/{}] {}: mean {} ms, p95 {} ms, max {} ms, cpu {} ms, {} visible + {} hidden changes a tick",
                jobIndex + 1, JOBS.size(), label(job), f(v.get("meanMs")), f(v.get("p95Ms")), f(v.get("maxMs")),
                f(v.get("cpuMs")), f(v.get("visibleChanges")), f(v.get("hiddenChanges")));
    }

    /** Fully loaded chunks of a level in and around the scenario's area (the kept square and 4 chunks round it). */
    private static int fullChunks(ServerLevel level, Scenario scenario) {
        ChunkPos centre = ChunkPos.containing(ctx.origin);
        int full = 0;
        for (int x = -scenario.rx - 4; x <= scenario.rx + 4; x++) {
            for (int z = -scenario.rz - 4; z <= scenario.rz + 4; z++) {
                if (level.getChunkSource().getChunkNow(centre.x() + x, centre.z() + z) != null) {
                    full++;
                }
            }
        }
        return full;
    }

    private static String label(Job job) {
        return job.scenario().name + " / " + job.variant().name() + " / round " + job.round();
    }

    private static long gc(boolean collections) {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, collections ? bean.getCollectionCount() : bean.getCollectionTime());
        }
        return total;
    }

    /** How many plates and tripwire strings PhasedInput looks at every tick (its private list, read by reflection). */
    private static long knownPlates() {
        try {
            java.lang.reflect.Field field = dev.phasecraft.phased.PhasedInput.class.getDeclaredField("PLATES");
            field.setAccessible(true);
            long total = 0;
            for (Object perDimension : ((Map<?, ?>) field.get(null)).values()) {
                if (perDimension instanceof Map<?, ?> chunks) {
                    for (Object set : chunks.values()) {
                        total += ((java.util.Collection<?>) set).size();
                    }
                } else {
                    total += ((java.util.Collection<?>) perDimension).size();
                }
            }
            return total;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static long linkStat(String name) {
        Matcher matcher = Pattern.compile(name + "=(\\d+)").matcher(PhaseLink.stats());
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : value >= 1 ? "%.2f" : "%.3f", value);
    }

    private static void writeSummary() {
        StringBuilder out = new StringBuilder();
        out.append("# PhaseCraft server tick measurements\n\n");
        out.append(String.format(Locale.ROOT, "%d rounds, %d ticks each (unless a scenario fixes its own), %s ticks a second, "
                + "Java %s, %d processors, heap %d MB.%n%n", ROUNDS, TICKS, f(RATE), Runtime.version(),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576));
        out.append("Per variant, over its rounds: **mean** the average tick (the mean of the rounds' means), **rounds** the "
                + "lowest and highest round mean (the machine's noise), **p50** the middle tick, **p95**/**max** the worst "
                + "round's, **cpu** the server thread's CPU time a tick, **alloc** what it allocated a tick, **proc** the "
                + "whole process's CPU time a tick (light, network and worker threads included). Times in ms.\n\n");
        Map<String, Map<String, List<Result>>> grouped = new LinkedHashMap<>();
        for (Result result : RESULTS) {
            grouped.computeIfAbsent(result.job().scenario().name, key -> new LinkedHashMap<>())
                    .computeIfAbsent(result.job().variant().name(), key -> new ArrayList<>()).add(result);
        }
        for (Map.Entry<String, Map<String, List<Result>>> scenario : grouped.entrySet()) {
            Scenario found = scenario.getValue().values().iterator().next().get(0).job().scenario();
            out.append("## ").append(scenario.getKey()).append("\n\n").append(found.what).append("\n\n");
            out.append("| variant | mean | rounds | p50 | p95 | max | cpu | alloc KB | proc | other |\n");
            out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (Map.Entry<String, List<Result>> variant : scenario.getValue().entrySet()) {
                List<Result> rounds = variant.getValue();
                TreeSet<String> keys = new TreeSet<>();
                rounds.forEach(result -> keys.addAll(result.values().keySet()));
                List<String> fixed = List.of("ticks", "meanMs", "p50Ms", "p95Ms", "maxMs", "cpuMs", "allocKb", "processCpuMs");
                StringBuilder other = new StringBuilder();
                for (String key : keys) {
                    double value = mean(rounds, key);
                    if (!fixed.contains(key) && value != 0) {
                        other.append(key).append(' ').append(f(value)).append("; ");
                    }
                }
                out.append(String.format(Locale.ROOT, "| %s | %s | %s to %s | %s | %s | %s | %s | %s | %s | %s |%n", variant.getKey(),
                        f(mean(rounds, "meanMs")), f(min(rounds, "meanMs")), f(max(rounds, "meanMs")), f(mean(rounds, "p50Ms")),
                        f(max(rounds, "p95Ms")), f(max(rounds, "maxMs")), f(mean(rounds, "cpuMs")), f(mean(rounds, "allocKb")),
                        f(mean(rounds, "processCpuMs")), other));
            }
            out.append('\n');
        }
        try {
            Files.writeString(OUT.resolve("results.md"), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double mean(List<Result> results, String key) {
        return results.stream().mapToDouble(result -> result.values().getOrDefault(key, 0.0)).average().orElse(0);
    }

    private static double min(List<Result> results, String key) {
        return results.stream().mapToDouble(result -> result.values().getOrDefault(key, 0.0)).min().orElse(0);
    }

    private static double max(List<Result> results, String key) {
        return results.stream().mapToDouble(result -> result.values().getOrDefault(key, 0.0)).max().orElse(0);
    }
}
