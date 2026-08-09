package test.vram.tweak.client.gpu;

import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps GL_AMD_pinned_memory — allows using application-allocated memory
 * directly as GPU buffer storage, skipping the driver copy.
 *
 * Use case: reduce CPU→GPU transfer latency for large texture atlases
 * and chunk geometry buffers. The actual PBO pool lives in
 * {@link PinnedMemoryPool}; this class only reports availability and
 * hosts the cross-mixin upload-handled flag.
 */
public class PinnedMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/pinned");

    private static boolean available;
    private static boolean checked;

    /**
     * Check if GL_AMD_pinned_memory is supported by the current driver.
     * Only relevant on AMD GPUs, but the extension query is safe on any vendor.
     */
    public static boolean isAvailable() {
        if (!checked) {
            checked = true;
            try {
                available = GL.getCapabilities().GL_AMD_pinned_memory;
                if (available) {
                    LOGGER.info("[PinnedMemory] GL_AMD_pinned_memory available — zero-copy buffer transfers supported");
                }
            } catch (Exception e) {
                LOGGER.warn("[PinnedMemory] Check failed", e);
                available = false;
            }
        }
        return available;
    }

    /** Called at startup to force the availability check and log the result. */
    public static void initCheck() {
        if (isAvailable()) {
            var cfg = test.vram.tweak.config.VRAMConfig.getInstance();
            if (cfg.showExperimental && cfg.experimental.pinnedMemory) {
                LOGGER.info("[PinnedMemory] ✅ Enabled (min size: {}px)", cfg.experimental.pinnedMemoryMinSize);
                PinnedMemoryPool.init();
            } else if (!cfg.showExperimental) {
                LOGGER.info("[PinnedMemory] ⏸ Experimental features hidden — enable 'ℹ Info → Show Experimental' to use");
            } else {
                LOGGER.info("[PinnedMemory] ✅ Extension available — enable 'Experimental (AMD) → Pinned Memory' in GUI to use");
            }
        } else {
            LOGGER.info("[PinnedMemory] ❌ Not available (requires AMD GPU + driver support)");
        }
    }

    // ---- Cross-mixin "upload already handled" flag ----
    // Set by MixinGlStateManager_PinnedMemory when it routes an upload through the
    // PBO pool and cancels the original call; consumed by MixinGlStateManager_Eviction
    // so it doesn't re-upload the same texture for RGB5A1 compression (avoids the
    // double-upload when both experimental features are on).
    private static final ThreadLocal<Boolean> UPLOAD_HANDLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Mark the current thread's upload as already handled (pinned PBO path). */
    public static void markUploadHandled() {
        UPLOAD_HANDLED.set(Boolean.TRUE);
    }

    /** True if the current upload was handled by the pinned PBO path; clears the flag. */
    public static boolean consumeUploadHandled() {
        boolean handled = UPLOAD_HANDLED.get();
        UPLOAD_HANDLED.set(Boolean.FALSE);
        return handled;
    }
}
