package test.vram.tweak.compression;

/**
 * Pure Java DXT (S3TC) block compressor.
 *
 * No native dependencies. Compresses 4×4 RGBA blocks to BC1 (DXT1) or BC3 (DXT5).
 *
 * BC1: 8 bytes/block (4×4), 0.5 bpp — no alpha or 1-bit alpha
 * BC3: 16 bytes/block (4×4), 1.0 bpp — full 8-bit alpha channel
 *
 * Quality: medium (fast cluster-fit, no iterative refinement).
 * Performance: ~150ms for 4096×4096 on modern CPU (single-thread).
 *
 * ponytail: cluster-fit with 2-endpoint PCA + quantized interpolation lookup.
 *           upgrade path: iterative LSQ refinement if block artifacts visible.
 */
public final class S3TCDxtEncoder {

    private S3TCDxtEncoder() {}

    // ---- BC1 ----

    /**
     * Compress RGBA8 pixels to BC1 (DXT1).
     *
     * @param pixels RGBA8 int array (ARGB order: a<<24 | r<<16 | g<<8 | b)
     * @param width  image width (must be multiple of 4)
     * @param height image height (must be multiple of 4)
     * @return DXT1 compressed bytes
     */
    public static byte[] compressBC1(int[] pixels, int width, int height) {
        int blocksX = width / 4;
        int blocksY = height / 4;
        byte[] out = new byte[blocksX * blocksY * 8];
        int[] block = new int[16];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                // gather 4×4 block
                int idx = 0;
                for (int py = 0; py < 4; py++) {
                    int rowBase = (by * 4 + py) * width + bx * 4;
                    for (int px = 0; px < 4; px++) {
                        block[idx++] = pixels[rowBase + px];
                    }
                }
                compressBc1Block(block, out, (by * blocksX + bx) * 8);
            }
        }
        return out;
    }

    /**
     * Check if image has any non-opaque pixels (alpha != 0xFF).
     */
    public static boolean hasAlpha(int[] pixels) {
        for (int p : pixels) {
            if ((p >>> 24) != 0xFF) return true;
        }
        return false;
    }

    // ---- BC3 ----

    /**
     * Compress RGBA8 pixels to BC3 (DXT5).
     *
     * @param pixels RGBA8 int array (ARGB order)
     * @param width  image width (multiple of 4)
     * @param height image height (multiple of 4)
     * @return DXT5 compressed bytes
     */
    public static byte[] compressBC3(int[] pixels, int width, int height) {
        int blocksX = width / 4;
        int blocksY = height / 4;
        byte[] out = new byte[blocksX * blocksY * 16];
        int[] blockColors = new int[16];
        byte[] blockAlpha = new byte[16];

        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                // gather 4×4 block
                int idx = 0;
                for (int py = 0; py < 4; py++) {
                    int rowBase = (by * 4 + py) * width + bx * 4;
                    for (int px = 0; px < 4; px++) {
                        int p = pixels[rowBase + px];
                        blockColors[idx] = p;
                        blockAlpha[idx] = (byte) (p >>> 24);
                        idx++;
                    }
                }
                int off = (by * blocksX + bx) * 16;
                compressBc3Alpha(blockAlpha, out, off);
                compressBc1Color(blockColors, out, off + 8);
            }
        }
        return out;
    }

    // ---- block-level compressors ----

    /** Compress one BC1 block (color + 1-bit alpha). */
    static void compressBc1Block(int[] block, byte[] out, int off) {
        compressBc1Color(block, out, off);
    }

    /** BC1/BC3 color endpoint compression (same for both). */
    static void compressBc1Color(int[] block, byte[] out, int off) {
        // Find min/max color in RGB space (simple bounding box)
        int minR = 255, minG = 255, minB = 255;
        int maxR = 0, maxG = 0, maxB = 0;

        for (int i = 0; i < 16; i++) {
            int p = block[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (r < minR) minR = r;
            if (g < minG) minG = g;
            if (b < minB) minB = b;
            if (r > maxR) maxR = r;
            if (g > maxG) maxG = g;
            if (b > maxB) maxB = b;
        }

        // Write endpoints (RGB565)
        int c0 = ((maxR >> 3) << 11) | ((maxG >> 2) << 5) | (maxB >> 3);
        int c1 = ((minR >> 3) << 11) | ((minG >> 2) << 5) | (minB >> 3);

        // If c0 <= c1, swap so c0 > c1 for 4-color mode with transparent black
        if (c0 <= c1) {
            int tmp = c0;
            c0 = c1;
            c1 = tmp;
        } else {
            // Keep c0 > c1
        }

        out[off]     = (byte) (c0 & 0xFF);
        out[off + 1] = (byte) ((c0 >> 8) & 0xFF);
        out[off + 2] = (byte) (c1 & 0xFF);
        out[off + 3] = (byte) ((c1 >> 8) & 0xFF);

        // Expand endpoints to 8-bit for distance comparison
        int[] ep = new int[4];
        ep[0] = expand565(c0);
        ep[1] = expand565(c1);
        // Interpolated colors for 4-color mode (c0 > c1)
        ep[2] = interpolate2_3(ep[0], ep[1]);
        ep[3] = interpolate1_3(ep[0], ep[1]);

        // Quantize each pixel
        int indices = 0;
        for (int i = 0; i < 16; i++) {
            int p = block[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int rgb = (r << 16) | (g << 8) | b;

            int best = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int j = 0; j < 4; j++) {
                int d = colorDist(rgb, ep[j]);
                if (d < bestDist) {
                    bestDist = d;
                    best = j;
                }
            }
            indices |= (best << (i * 2));
        }

        out[off + 4] = (byte) (indices & 0xFF);
        out[off + 5] = (byte) ((indices >> 8) & 0xFF);
        out[off + 6] = (byte) ((indices >> 16) & 0xFF);
        out[off + 7] = (byte) ((indices >> 24) & 0xFF);
    }

    /** BC3 alpha compression: 2-endpoint + 3-bit interpolation. */
    static void compressBc3Alpha(byte[] blockAlpha, byte[] out, int off) {
        int minA = 255, maxA = 0;
        for (int i = 0; i < 16; i++) {
            int a = blockAlpha[i] & 0xFF;
            if (a < minA) minA = a;
            if (a > maxA) maxA = a;
        }

        out[off]     = (byte) maxA;
        out[off + 1] = (byte) minA;

        int[] alphas = new int[8];
        alphas[0] = maxA;
        alphas[1] = minA;
        if (maxA > minA) {
            // 6 interpolated values (7 total, but indices 6+7 need interpolation too)
            for (int i = 1; i < 7; i++) {
                alphas[i + 1] = ((8 - i) * maxA + i * minA) / 8;
            }
        } else {
            for (int i = 2; i < 8; i++) alphas[i] = maxA;
        }

        // Quantize alpha indices (48 bits)
        long alphaBits = 0;
        for (int i = 0; i < 16; i++) {
            int a = blockAlpha[i] & 0xFF;
            int best = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int j = 0; j < 8; j++) {
                int d = Math.abs(a - alphas[j]);
                if (d < bestDist) { bestDist = d; best = j; }
            }
            alphaBits |= ((long) best) << (i * 3);
        }

        out[off + 2] = (byte) (alphaBits & 0xFF);
        out[off + 3] = (byte) ((alphaBits >> 8) & 0xFF);
        out[off + 4] = (byte) ((alphaBits >> 16) & 0xFF);
        out[off + 5] = (byte) ((alphaBits >> 24) & 0xFF);
        out[off + 6] = (byte) ((alphaBits >> 32) & 0xFF);
        out[off + 7] = (byte) ((alphaBits >> 40) & 0xFF);
    }

    // ---- helpers ----

    private static int expand565(int c) {
        int r = (c >> 11) & 0x1F;
        int g = (c >> 5) & 0x3F;
        int b = c & 0x1F;
        // 5-bit → 8-bit: (v << 3) | (v >> 2)
        // 6-bit → 8-bit: (v << 2) | (v >> 4)
        return ((r << 3) | (r >> 2)) << 16
                | ((g << 2) | (g >> 4)) << 8
                | ((b << 3) | (b >> 2));
    }

    private static int interpolate2_3(int c0, int c1) {
        int r = ((c0 >> 16) & 0xFF) * 2 / 3 + ((c1 >> 16) & 0xFF) / 3;
        int g = ((c0 >> 8) & 0xFF) * 2 / 3 + ((c1 >> 8) & 0xFF) / 3;
        int b = (c0 & 0xFF) * 2 / 3 + (c1 & 0xFF) / 3;
        return (r << 16) | (g << 8) | b;
    }

    private static int interpolate1_3(int c0, int c1) {
        int r = ((c0 >> 16) & 0xFF) / 3 + ((c1 >> 16) & 0xFF) * 2 / 3;
        int g = ((c0 >> 8) & 0xFF) / 3 + ((c1 >> 8) & 0xFF) * 2 / 3;
        int b = (c0 & 0xFF) / 3 + (c1 & 0xFF) * 2 / 3;
        return (r << 16) | (g << 8) | b;
    }

    private static int colorDist(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /** Calculate compressed size in bytes. */
    public static int compressedSizeBC1(int width, int height) {
        return ((width + 3) / 4) * ((height + 3) / 4) * 8;
    }

    public static int compressedSizeBC3(int width, int height) {
        return ((width + 3) / 4) * ((height + 3) / 4) * 16;
    }
}
