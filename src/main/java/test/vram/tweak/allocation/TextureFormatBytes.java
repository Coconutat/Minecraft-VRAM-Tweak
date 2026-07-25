package test.vram.tweak.allocation;

/**
 * OpenGL internal-format → bytes-per-pixel lookup table.
 *
 * <p>Covers the most common formats seen in Minecraft/Iris rendering.
 * Unknown formats fall back to 4 bytes/pixel (conservative, matches RGBA8).</p>
 *
 * <p>Reference: OpenGL 4.6 spec, Table 8.12 (sized internal formats).
 * Compressed format pixel sizes are approximate averages.</p>
 */
public final class TextureFormatBytes {

    private TextureFormatBytes() { /* utility class */ }

    /**
     * Returns the estimated bytes per pixel (for level-0 mipmap) for the
     * given GL internal format constant.
     *
     * <p>Use standard LWJGL/OpenGL format constants, e.g.:
     * {@code GL30.GL_RGBA8}, {@code GL30.GL_DEPTH_COMPONENT24}.</p>
     *
     * @param glInternalFormat OpenGL internal format enum value
     * @return estimated bytes per pixel, minimum 1
     */
    public static float lookup(int glInternalFormat) {
        return switch (glInternalFormat) {
            // --- 8-bit per component uncompressed ---
            case 0x8058 /*GL_RGBA8*/,  0x8051 /*GL_RGB8*/,  0x8229 /*GL_R8*/,
                 0x822D /*GL_R16F*/,   0x822E /*GL_R32F*/,  0x8230 /*GL_RG8*/,
                 0x8233 /*GL_RG16F*/,  0x8234 /*GL_RG32F*/, 0x881A /*GL_RGBA16F*/  -> 8.0f;
            case 0x80CB /*GL_DEPTH_COMPONENT24*/ -> 3.0f;
            case 0x81A5 /*GL_DEPTH_COMPONENT32*/ -> 4.0f;
            case 0x84F9 /*GL_DEPTH_STENCIL*/    -> 4.0f;
            case 0x822A /*GL_R16*/              -> 2.0f;
            case 0x8231 /*GL_RG16*/             -> 4.0f;
            case 0x823A /*GL_RGBA16UI*/          -> 8.0f;

            // --- S3TC/DXT compressed (approximate, varies by content) ---
            case 0x83F0 /*GL_COMPRESSED_RGB_S3TC_DXT1_EXT*/  -> 0.5f;
            case 0x83F2 /*GL_COMPRESSED_RGBA_S3TC_DXT3_EXT*/ -> 1.0f;
            case 0x83F3 /*GL_COMPRESSED_RGBA_S3TC_DXT5_EXT*/ -> 1.0f;

            default -> {
                // Heuristic: if the high byte suggests a common format, guess
                yield 4.0f; // conservative fallback (RGBA8 equivalent)
            }
        };
    }
}
