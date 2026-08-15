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
    /**
     * Blaze3D GpuFormat 名称 → 每像素字节数。未知格式保守按 4 bpp 计。
     */
    public static float lookupByName(String formatName) {
        if (formatName == null || formatName.isEmpty()) return 4.0f;
        return switch (formatName) {
            case "R8_UNORM", "R8" -> 1.0f;
            case "R16_UNORM", "R16", "R16F", "RG8_UNORM", "RG8", "D16_UNORM" -> 2.0f;
            case "RGB8_UNORM", "RGB8" -> 3.0f;
            case "RGBA8_UNORM", "RGBA8", "R32F", "R32_UINT", "R32_SINT",
                 "RG16_UNORM", "RG16", "RG16F", "D32_FLOAT", "D24_UNORM_S8_UINT" -> 4.0f;
            case "RGBA16_UNORM", "RGBA16", "RGBA16F", "RG32F", "RG32_UINT", "RG32_SINT" -> 8.0f;
            case "RGBA32F", "RGBA32_UINT", "RGBA32_SINT" -> 16.0f;
            default -> 4.0f; // conservative fallback (RGBA8 equivalent)
        };
    }

    public static float lookup(int glInternalFormat) {
        return switch (glInternalFormat) {
            // --- 1 byte/pixel ---
            case 0x8229 /*GL_R8*/ -> 1.0f;

            // --- 2 bytes/pixel ---
            case 0x822D /*GL_R16F*/,  0x8230 /*GL_RG8*/,
                 0x822A /*GL_R16*/                 -> 2.0f;
            case 0x81A6 /*GL_DEPTH_COMPONENT16*/   -> 2.0f;

            // --- 3 bytes/pixel ---
            case 0x8051 /*GL_RGB8*/                -> 3.0f;
            case 0x80CB /*GL_DEPTH_COMPONENT24*/   -> 3.0f;

            // --- 4 bytes/pixel ---
            case 0x8058 /*GL_RGBA8*/,  0x822E /*GL_R32F*/,
                 0x8233 /*GL_RG16F*/, 0x8231 /*GL_RG16*/,
                 0x823B /*GL_RG16UI*/, 0x8232 /*GL_RG16I*/
                                                   -> 4.0f;
            case 0x81A5 /*GL_DEPTH_COMPONENT32*/   -> 4.0f;
            case 0x84F9 /*GL_DEPTH24_STENCIL8*/    -> 4.0f;
            case 0x8D48 /*GL_DEPTH32F_STENCIL8*/   -> 5.0f;

            // --- 8 bytes/pixel ---
            case 0x8234 /*GL_RG32F*/,  0x881A /*GL_RGBA16F*/,
                 0x823A /*GL_RGBA16UI*/, 0x8D7C /*GL_RGBA16_UNORM*/
                                                   -> 8.0f;

            // --- S3TC/DXT compressed (approximate, varies by content) ---
            case 0x83F0 /*GL_COMPRESSED_RGB_S3TC_DXT1_EXT*/  -> 0.5f;
            case 0x83F2 /*GL_COMPRESSED_RGBA_S3TC_DXT3_EXT*/ -> 1.0f;
            case 0x83F3 /*GL_COMPRESSED_RGBA_S3TC_DXT5_EXT*/ -> 1.0f;

            // --- Buffer objects: format=0 means raw bytes (1 byte per "pixel") ---
            case 0 -> 1.0f;

            default -> {
                yield 4.0f; // conservative fallback (RGBA8 equivalent)
            }
        };
    }
}
