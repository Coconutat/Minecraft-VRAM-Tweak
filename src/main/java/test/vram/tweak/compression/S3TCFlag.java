package test.vram.tweak.compression;

/**
 * Thread-local S3TC compression flags shared between mixins.
 *
 * ponytail: Mixin classes can't have non-private static fields/methods,
 *           so flags live here. Both mixins reference this utility.
 */
public final class S3TCFlag {
    private S3TCFlag() {}

    public static final ThreadLocal<Boolean> FLAG = new ThreadLocal<>();
    public static final ThreadLocal<Integer> WIDTH = new ThreadLocal<>();
    public static final ThreadLocal<Integer> HEIGHT = new ThreadLocal<>();

    public static void set(boolean flag, int width, int height) {
        FLAG.set(flag);
        WIDTH.set(width);
        HEIGHT.set(height);
    }

    public static boolean isSet() {
        return Boolean.TRUE.equals(FLAG.get());
    }

    public static void clear() {
        FLAG.remove();
        WIDTH.remove();
        HEIGHT.remove();
    }
}
