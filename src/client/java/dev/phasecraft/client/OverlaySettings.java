package dev.phasecraft.client;

import dev.phasecraft.PhaseCraft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Overlay settings, kept in config/phasecraft-client.properties: whether the
 * overlay is shown and how opaque the ghosts are.
 */
public final class OverlaySettings {
    public static final float MIN_OPACITY = 0.15F;
    public static final float MAX_OPACITY = 1.0F;
    public static final float DEFAULT_OPACITY = 0.45F;
    public static final float STEP = 0.1F;

    private final Path file;
    private boolean enabled = true;
    private float opacity = DEFAULT_OPACITY;

    public OverlaySettings(Path file) {
        this.file = file;
    }

    public static OverlaySettings load() {
        OverlaySettings settings = new OverlaySettings(FabricLoader.getInstance().getConfigDir().resolve("phasecraft-client.properties"));
        settings.read();
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }

    public float opacity() {
        return opacity;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setOpacity(float opacity) {
        // Rounded to whole percent so repeated steps do not drift.
        this.opacity = Math.round(Math.clamp(opacity, MIN_OPACITY, MAX_OPACITY) * 100) / 100F;
        save();
    }

    public void moreOpaque() {
        setOpacity(opacity + STEP);
    }

    public void lessOpaque() {
        setOpacity(opacity - STEP);
    }

    public int opacityPercent() {
        return Math.round(opacity * 100);
    }

    private void read() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            enabled = Boolean.parseBoolean(properties.getProperty("overlay", "true"));
            opacity = Math.clamp(Float.parseFloat(properties.getProperty("opacity", Float.toString(DEFAULT_OPACITY))),
                    MIN_OPACITY, MAX_OPACITY);
        } catch (IOException | NumberFormatException e) {
            PhaseCraft.LOGGER.warn("Could not read {}; using defaults: {}", file, e.toString());
        }
    }

    private void save() {
        Properties properties = new Properties();
        properties.setProperty("overlay", Boolean.toString(enabled));
        properties.setProperty("opacity", String.format(Locale.ROOT, "%.2f", opacity));
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "PhaseCraft client: hidden-layer overlay");
            }
        } catch (IOException e) {
            PhaseCraft.LOGGER.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
