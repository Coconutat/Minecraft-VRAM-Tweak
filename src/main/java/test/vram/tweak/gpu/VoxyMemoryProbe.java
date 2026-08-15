package test.vram.tweak.gpu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads Voxy's (voxyworldgenv2) own GPU-memory stats via reflection, so vram-tweak
 * can report how much VRAM Voxy's LOD mesh buffers + immutable textures occupy.
 *
 * <p>Voxy exposes {@code GlBuffer.getTotalSize()/getCount()} (public static) and keeps
 * {@code GlTexture.ESTIMATED_TOTAL_SIZE/COUNT} as private statics — both live in
 * {@code me.cortex.voxy.client.core.gl}. All access is reflective + cached; if Voxy
 * is absent or its class layout changes, everything degrades to disabled/0.</p>
 *
 * <p>ponytail: reflection instead of a Mixin @Accessor — avoids static-accessor
 * interface pitfalls and Voxy is an optional mod (no mixin to an external jar).</p>
 */
public final class VoxyMemoryProbe {
    private static boolean checked;
    private static boolean available;

    private static Method glBufferTotalSize;
    private static Method glBufferCount;
    private static Field glTextureEstimatedSize;
    private static Field glTextureCount;

    private VoxyMemoryProbe() {}

    /** True if Voxy's GL stat classes are present and readable. */
    public static boolean isAvailable() {
        if (!checked) {
            checked = true;
            try {
                Class<?> buf = Class.forName("me.cortex.voxy.client.core.gl.GlBuffer");
                glBufferTotalSize = buf.getMethod("getTotalSize");
                glBufferCount = buf.getMethod("getCount");
                Class<?> tex = Class.forName("me.cortex.voxy.client.core.gl.GlTexture");
                glTextureEstimatedSize = tex.getDeclaredField("ESTIMATED_TOTAL_SIZE");
                glTextureEstimatedSize.setAccessible(true);
                glTextureCount = tex.getDeclaredField("COUNT");
                glTextureCount.setAccessible(true);
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /** Total bytes of all Voxy GPU buffers (GlBuffer.TOTAL_SIZE). */
    public static long getBufferBytes() {
        try { return (Long) glBufferTotalSize.invoke(null); }
        catch (Exception e) { return 0; }
    }

    /** Number of live Voxy GPU buffers. */
    public static int getBufferCount() {
        try { return (Integer) glBufferCount.invoke(null); }
        catch (Exception e) { return 0; }
    }

    /** Estimated total bytes of Voxy immutable textures (GlTexture.ESTIMATED_TOTAL_SIZE). */
    public static long getTextureBytes() {
        try { return glTextureEstimatedSize.getLong(null); }
        catch (Exception e) { return 0; }
    }

    /** Number of live Voxy textures. */
    public static int getTextureCount() {
        try { return glTextureCount.getInt(null); }
        catch (Exception e) { return 0; }
    }
}
