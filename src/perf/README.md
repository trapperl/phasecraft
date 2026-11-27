# Server performance measurements (`./gradlew runPerf`)

What PhaseCraft costs a server per tick in heavy cases. A separate mod (`phasecraft-perf`, this folder) that is
never shipped and not part of `./gradlew build` (only compiled there): it takes many minutes, and its numbers mean
nothing on a busy machine or a CI runner.

```
./gradlew runPerf                                   # everything: 3 rounds, 300 ticks, 20 ticks a second (about an hour)
./gradlew runPerf -Pperf.scenarios=wire,twins       # only scenarios whose name contains one of these
./gradlew runPerf -Pperf.rounds=1 -Pperf.ticks=150 -Pperf.rate=200     # a quick look (a few minutes)
./gradlew runPerf -Pperf.jfr=true -Pperf.rate=1000 -Pperf.ticks=600 -Pperf.rounds=1   # profile every measurement
./gradlew runPerf -Pperf.tag=b -Pperf.port=25598    # a second run beside another (build/perf-b, build/run/perf-b)
```

It starts a dedicated server on a fresh flat world (`build/run/perf`, offline, bound to 127.0.0.1, no Mojang
traffic: the same switches as the test servers), runs every scenario and stops. Results: `build/perf/results.md`
(tables), `build/perf/results.jsonl` (one line a measurement), and with `-Pperf.jfr=true` a Java Flight Recorder
file and a summary of the server thread's samples for every measurement in `build/perf/jfr/`.

## How it measures

- Every measurement builds its scenario from nothing in its own area of the world (2,048 blocks from the last),
  with the chunks kept loaded as `/forceload` does and no real players, lets it settle, then records every tick:
  wall time from Fabric's start-of-tick event to after the last end-of-tick handler (PhaseCraft's own included),
  the server thread's CPU time and what it allocated, plus the whole process's CPU time (light, worker and network
  threads).
- "Without PhaseCraft's part" is the same scenario with that part switched off by a mixin in this mod (`Switches`:
  the block-change hooks, the port look-up on visible signal reads, the hidden chunks following the visible ones,
  the overlay sync, the plate check), or the nearest vanilla thing (hoppers, lamps and pistons in place of their
  phased or shifting kin). The shipped mod has no such switches.
- Variants of a scenario are measured one after the other, and the whole set again every round, so the "rounds"
  column shows how far the machine's noise moves a mean. Trust differences larger than that spread.
- Players are real `ServerPlayer`s on a connection that goes nowhere, marked as having PhaseCraft's channels (as
  the GameTests' `TestPlayers`). What is sent to them is counted, and PhaseCraft's messages are encoded by the
  harness, outside the measured time, to know their size and what encoding costs the network threads.
- At 20 ticks a second the server sleeps between ticks, as a real one does (cold caches, like a real server); at
  a high `-Pperf.rate` it runs ticks back to back (warmer, lower numbers, many more profiler samples a second).

## Scenarios

See `Scenarios.java`; each one's description is copied into `results.md`. To add one: a `Scenario` with a name,
the chunks it needs, and variants (switches, a build, an optional per-tick stimulus).
