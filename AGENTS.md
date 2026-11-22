# Agent instructions for PhaseCraft

You are contributing to an experimental Minecraft Java Edition **Fabric** mod.
The goal is a hidden block layer sharing XYZ coordinates with ordinary world
blocks, with future vanilla-compatible cross-layer redstone and pistons.

## Invariants
- Never modify the visible world's blocks when adding/removing a hidden cell.
- Persist hidden data per dimension and keep writes on the logical server.
- Preserve vanilla piston rules; do not add phase pistons until storage and
  interactions are demonstrated and tested.
- Never claim something is working without a passing build/test in CI or a
  reproducible local run. Do not silently bypass failures.
- Keep milestones small and open PRs; do not force-push or merge to main.
- Never use commands that wipe player saves or unrelated files.

## Stack
- Minecraft Java **26.3** / Fabric / Java **25** / Gradle **9.8.2**.
- Mojang-mapped source as provided by Fabric 26.3 (no `mappings` declaration).
- New unobfuscated Loom plugin ID: `net.fabricmc.fabric-loom`.
- Tests under src/test/java use JUnit 5.
- An in-game GameTest harness for cross-layer behavior and save/reload is a
  **next milestone**, not something the current unit tests prove.

## Next work
1. Confirm 26.3 Gradle build succeeds in GitHub Actions and fix failures.
2. Add repeatable GameTests for hidden/visible coexistence and world reload.
3. Preserve complete BlockState properties, not just block registry IDs.
4. Design hidden-layer ticking and cross-layer power transport with explicit
   feedback-loop protection.
5. Prototype one phase piston using vanilla push rules.
6. Add client rendering and editing only after server behavior is sound.
