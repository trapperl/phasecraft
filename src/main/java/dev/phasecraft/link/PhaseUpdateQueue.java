package dev.phasecraft.link;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pending cross-layer updates, processed once per game tick.
 *
 * <p>This is the feedback-loop protection: anything added while a batch is
 * being processed waits for the next drain. A loop through both layers
 * therefore advances at most one hop per tick, like a clock, instead of
 * recursing within a tick. Duplicates within one batch collapse.</p>
 *
 * @param <T> update type
 */
public final class PhaseUpdateQueue<T> {
    private Set<T> pending = new LinkedHashSet<>();

    public void add(T update) {
        pending.add(update);
    }

    public int size() {
        return pending.size();
    }

    /** Processes the current batch; returns how many updates it held. */
    public int drain(Consumer<T> handler) {
        if (pending.isEmpty()) {
            return 0;
        }
        Set<T> batch = pending;
        pending = new LinkedHashSet<>();
        batch.forEach(handler);
        return batch.size();
    }
}
