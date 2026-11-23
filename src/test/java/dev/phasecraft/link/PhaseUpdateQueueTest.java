package dev.phasecraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhaseUpdateQueueTest {
    @Test
    void duplicatesInOneBatchAreProcessedOnce() {
        PhaseUpdateQueue<String> queue = new PhaseUpdateQueue<>();
        queue.add("a");
        queue.add("a");
        queue.add("b");
        List<String> seen = new ArrayList<>();
        assertEquals(2, queue.drain(seen::add));
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void workAddedWhileDrainingWaitsForTheNextDrain() {
        PhaseUpdateQueue<Integer> queue = new PhaseUpdateQueue<>();
        queue.add(0);
        List<Integer> seen = new ArrayList<>();
        // Each update schedules the next: an endless cross-layer loop.
        queue.drain(n -> { seen.add(n); queue.add(n + 1); });
        assertEquals(List.of(0), seen);
        queue.drain(n -> { seen.add(n); queue.add(n + 1); });
        assertEquals(List.of(0, 1), seen);
        assertEquals(1, queue.size());
    }

    @Test
    void emptyQueueDrainsNothing() {
        assertEquals(0, new PhaseUpdateQueue<String>().drain(item -> { throw new AssertionError(item); }));
    }
}
