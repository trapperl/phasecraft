package dev.phasecraft.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * A Java Flight Recorder profile of one measurement, and what it says of the server thread: which methods the
 * samples were in (self time), and how many had PhaseCraft somewhere on the stack (PhaseCraft's share of the tick,
 * with the vanilla code it called). Samples taken inside the harness's own work (building, stimulus) are left out.
 */
final class JfrSummary {
    private JfrSummary() {}

    static Recording start() {
        Recording recording = new Recording();
        recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
        recording.start();
        return recording;
    }

    /** Stops, writes name.jfr and name.txt, and returns PhaseCraft's share of the server thread's samples (0 to 1). */
    static double finish(Recording recording, Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".jfr");
        recording.stop();
        recording.dump(file);
        recording.close();

        Map<String, Integer> self = new HashMap<>();
        Map<String, Integer> inclusive = new HashMap<>();
        int total = 0;
        int phasecraft = 0;
        try (RecordingFile in = new RecordingFile(file)) {
            while (in.hasMoreEvents()) {
                RecordedEvent event = in.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    continue;
                }
                RecordedThread thread = event.getThread("sampledThread");
                RecordedStackTrace stack = event.getStackTrace();
                if (thread == null || stack == null || !"Server thread".equals(thread.getJavaName())) {
                    continue;
                }
                List<String> frames = new ArrayList<>();
                boolean harness = false;
                for (RecordedFrame frame : stack.getFrames()) {
                    String type = frame.getMethod().getType().getName();
                    frames.add(type + "." + frame.getMethod().getName());
                    harness |= type.startsWith("dev.phasecraft.perf.") && !type.contains(".mixin.");
                }
                if (harness || frames.isEmpty()) {
                    continue;
                }
                total++;
                self.merge(frames.get(0), 1, Integer::sum);
                Set<String> ours = new HashSet<>();
                for (String frame : frames) {
                    if (isPhaseCraft(frame)) {
                        ours.add(frame);
                    }
                }
                if (!ours.isEmpty()) {
                    phasecraft++;
                    for (String frame : ours) {
                        inclusive.merge(frame, 1, Integer::sum);
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(name).append('\n');
        out.append("server thread samples (1 ms apart, harness work left out): ").append(total).append('\n');
        out.append(String.format("with PhaseCraft on the stack: %d (%.1f%%)%n", phasecraft, percent(phasecraft, total)));
        out.append("\nPhaseCraft methods, inclusive (samples with the method anywhere on the stack):\n");
        top(out, inclusive, total, 30);
        out.append("\nSelf time, all methods:\n");
        top(out, self, total, 40);
        Files.writeString(dir.resolve(name + ".txt"), out);
        return total == 0 ? 0 : (double) phasecraft / total;
    }

    /** PhaseCraft's own classes, and its mixin handlers merged into vanilla classes (their names carry "phasecraft$"). */
    private static boolean isPhaseCraft(String frame) {
        if (frame.startsWith("dev.phasecraft.perf.")) {
            return false;
        }
        // (ServerLevelPhaseBridgeMixin's getSignal override is vanilla's own body plus PhaseLink.upAt, which counts.)
        return frame.startsWith("dev.phasecraft.") || frame.contains("phasecraft$");
    }

    private static void top(StringBuilder out, Map<String, Integer> counts, int total, int limit) {
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit)
                .forEach(e -> out.append(String.format("%6d %5.1f%%  %s%n", e.getValue(), percent(e.getValue(), total), e.getKey())));
    }

    private static double percent(int part, int total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }
}
