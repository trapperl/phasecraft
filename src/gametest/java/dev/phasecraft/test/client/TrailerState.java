package dev.phasecraft.test.client;

/** What the trailer's renderer (TrailerClientGameTest) tells the test mod's mixins; all off unless it runs. */
public final class TrailerState {
    /** This game was started to render the trailer (tools/trailer.py). */
    public static final boolean RENDERING = System.getenv("PHASECRAFT_TRAILER") != null && !System.getenv("PHASECRAFT_TRAILER").isBlank();
    /** The camera's field of view in degrees, or 0: the game's own. */
    public static volatile float fov;
    /** The shader pack's clock in seconds, or negative: the real one. */
    public static volatile float clock = -1;
    /** What the shader pack is told a frame took, in seconds. */
    public static volatile float frameSeconds = 1 / 60F;

    /** The game's own frames draw no world: the trailer draws its frames itself, and each is costly. */
    public static volatile boolean skipLevel;

    private TrailerState() {}
}
