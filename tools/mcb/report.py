"""Size reports for generated layouts (docs/BUILD_RULES.md, S1): how many hidden blocks, the box they fill, and
how many of them are only supports (glass)."""


def hidden_size(positions_and_states):
    """[(pos, state)] -> a one-line report."""
    items = list(positions_and_states)
    if not items:
        return "no hidden blocks"
    lo = [min(p[i] for p, _ in items) for i in range(3)]
    hi = [max(p[i] for p, _ in items) for i in range(3)]
    box = [hi[i] - lo[i] + 1 for i in range(3)]
    glass = sum(1 for _, s in items if s.split("[")[0].endswith(":glass"))
    return (f"{len(items)} hidden blocks ({glass} glass supports) in a {box[0]}x{box[1]}x{box[2]} box "
            f"({box[0] * box[1] * box[2]} cells)")
