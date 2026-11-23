package dev.phasecraft.layer;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The hidden dimension paired with each visible dimension.
 *
 * <p>Each is an empty void world (data/phasecraft/dimension/*.json) with the
 * same dimension type, so the same coordinates and height range as its
 * visible twin. Only the three vanilla dimensions have a hidden layer.</p>
 */
public final class HiddenDimensions {
    public static final ResourceKey<Level> HIDDEN_OVERWORLD = key("hidden_overworld");
    public static final ResourceKey<Level> HIDDEN_NETHER = key("hidden_the_nether");
    public static final ResourceKey<Level> HIDDEN_END = key("hidden_the_end");

    private static final Map<ResourceKey<Level>, ResourceKey<Level>> HIDDEN_OF = Map.of(
            Level.OVERWORLD, HIDDEN_OVERWORLD,
            Level.NETHER, HIDDEN_NETHER,
            Level.END, HIDDEN_END);
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> VISIBLE_OF = Map.of(
            HIDDEN_OVERWORLD, Level.OVERWORLD,
            HIDDEN_NETHER, Level.NETHER,
            HIDDEN_END, Level.END);

    private HiddenDimensions() {}

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("phasecraft", path));
    }

    /** Visible dimensions that have a hidden layer. */
    public static Iterable<ResourceKey<Level>> visibleDimensions() {
        return HIDDEN_OF.keySet();
    }

    public static boolean isHidden(ResourceKey<Level> dimension) {
        return VISIBLE_OF.containsKey(dimension);
    }

    public static Optional<ResourceKey<Level>> hiddenOf(ResourceKey<Level> visible) {
        return Optional.ofNullable(HIDDEN_OF.get(visible));
    }

    public static Optional<ResourceKey<Level>> visibleOf(ResourceKey<Level> hidden) {
        return Optional.ofNullable(VISIBLE_OF.get(hidden));
    }
}
