package test.vram.tweak.client.gpu;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.AMDPinnedMemory;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps GL_AMD_pinned_memory — allows using application-allocated memory
 * directly as GPU buffer storage, skipping the driver copy.
 *
 * Use case: reduce CPU→GPU transfer latency for large texture atlases
 * and chunk geometry buffers.
 *
 * Usage:
 *   1. Check isAvailable()
 *   2. Allocate a pinned PBO with createPinnedPBO(size, data)
 *   3. Bind it as GL_PIXEL_UNPACK_BUFFER for zero-copy texture upload
 */
public class PinnedMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/pinned");
    private static final int GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD = 0x9160;

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
            var cfg = test.vram.tweak.config.VRAMConfig.getInstance().experimental;
            if (cfg.pinnedMemory) {
                LOGGER.info("[PinnedMemory] ✅ Enabled (min size: {}px)", cfg.pinnedMemoryMinSize);
                PinnedMemoryPool.init();
            } else {
                LOGGER.info("[PinnedMemory] ✅ Extension available — enable 'Experimental (AMD) → Pinned Memory' in GUI to use");
            }
        } else {
            LOGGER.info("[PinnedMemory] ❌ Not available (requires AMD GPU + driver support)");
        }
    }

    /**
     * Create a PBO backed by pinned application memory.
     * The buffer's memory is directly accessible by the GPU — no driver copy.
     *
     * @param size  buffer size in bytes
     * @param data  optional initial data, or null for uninitialized
     * @return      the PBO handle (GL name), or -1 on failure
     */
    public static int createPinnedPBO(int size, ByteBuffer data) {
        if (!isAvailable()) return -1;
        try {
            int pboId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, pboId);
            if (data != null) {
                GL15.glBufferData(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, data, GL15.GL_STREAM_DRAW);
            } else {
                GL15.glBufferData(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, size, GL15.GL_STREAM_DRAW);
            }
            // After binding to EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD and calling BufferData,
            // the buffer can be bound to other targets (e.g. PIXEL_UNPACK_BUFFER) normally.
            GL15.glBindBuffer(GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD, 0);
            return pboId;
        } catch (Exception e) {
            LOGGER.warn("[PinnedMemory] Failed to create pinned PBO", e);
            return -1;
        }
    }

    /**
     * Convenience: bind a pinned PBO as PIXEL_UNPACK_BUFFER for texture upload.
     * After this, glTexSubImage2D(..., 0) reads from the PBO instead of client memory.
     */
    public static void bindAsUnpackPBO(int pboId) {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
    }

    /**
     * Unbind PIXEL_UNPACK_BUFFER (restore client-memory uploads).
     */
    public static void unbindUnpackPBO() {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    /**
     * Cleanup: delete a pinned PBO.
     */
    public static void deletePBO(int pboId) {
        if (pboId >= 0) {
            GL15.glDeleteBuffers(pboId);
        }
    }
}
