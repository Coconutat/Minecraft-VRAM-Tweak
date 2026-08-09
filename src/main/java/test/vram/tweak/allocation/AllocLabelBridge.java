package test.vram.tweak.allocation;

/**
 * Thread-safe bridge between the A-layer (GpuDevice.createTexture) and
 * B-layer (GlStateManager._texImage2D) mixins.
 *
 * <p>Mixin classes cannot expose non-private static methods, so the shared
 * state lives here instead.</p>
 */
public final class AllocLabelBridge {

    private static final ThreadLocal<String> PENDING = new ThreadLocal<>();

    private AllocLabelBridge() { /* utility */ }

    /** Called from A-layer mixin to record the label for the next allocation. */
    public static void set(String label) {
        PENDING.set(label);
    }

    /** Called from B-layer mixin to consume the label. Returns null if none set. */
    public static String consume() {
        String label = PENDING.get();
        PENDING.remove();
        return label;
    }

    /** Peek at the pending label without consuming it. Returns null if none set. */
    public static String peek() {
        return PENDING.get();
    }
}
