package test.vram.tweak.allocation;

/**
 * GPU 内部格式 → 每像素字节数（level-0 mipmap）查询表。
 *
 * <p>同时支持 Blaze3D {@code GpuFormat} 枚举名（{@link #lookupByName}）与
 * OpenGL sized internal format 常量（{@link #lookup}）。未知格式保守按 4 bpp
 * 处理（等价 RGBA8）。压缩格式为近似平均字节/像素。</p>
 *
 * <p>参考：OpenGL 4.6 spec，Table 8.12（sized internal formats）。</p>
 */
public final class TextureFormatBytes {

    private TextureFormatBytes() { /* utility class */ }

    /**
     * Blaze3D GpuFormat 名称 → 每像素字节数。未知格式保守按 4 bpp 计。
     */
    public static float lookupByName(String formatName) {
        if (formatName == null || formatName.isEmpty()) return 4.0f;
        return switch (formatName) {
            // ---- 1 byte/pixel ----
            case "R8", "R8_UNORM", "R8_UINT", "R8_SINT", "R8_SNORM" -> 1.0f;

            // ---- 2 bytes/pixel ----
            case "R16", "R16_UNORM", "R16_UINT", "R16_SINT", "R16_SNORM",
                 "R16F", "R16_FLOAT", "RG8", "RG8_UNORM", "RG8_UINT", "RG8_SINT", "RG8_SNORM",
                 "RGBA4", "RGB5_A1", "RGB565", "D16_UNORM" -> 2.0f;

            // ---- 3 bytes/pixel ----
            case "RGB8", "RGB8_UNORM", "RGB8_UINT", "RGB8_SINT", "RGB8_SNORM" -> 3.0f;

            // ---- 4 bytes/pixel ----
            case "RGBA8", "RGBA8_UNORM", "RGBA8_UINT", "RGBA8_SINT", "RGBA8_SNORM",
                 "R32F", "R32_FLOAT", "R32_UINT", "R32_SINT",
                 "RG16", "RG16_UNORM", "RG16_UINT", "RG16_SINT", "RG16_SNORM", "RG16F", "RG16_FLOAT",
                 "RGB10_A2", "RGB10_A2UI", "R11F_G11F_B10F", "RGB9_E5",
                 "D32_FLOAT", "D24_UNORM_S8_UINT" -> 4.0f;

            // ---- 5 bytes/pixel ----
            case "D32_FLOAT_S8_UINT" -> 5.0f;

            // ---- 6 bytes/pixel ----
            case "RGB16", "RGB16_UNORM", "RGB16_UINT", "RGB16_SINT", "RGB16_SNORM",
                 "RGB16F", "RGB16_FLOAT" -> 6.0f;

            // ---- 8 bytes/pixel ----
            case "RG32F", "RG32_FLOAT", "RG32_UINT", "RG32_SINT",
                 "RGBA16", "RGBA16_UNORM", "RGBA16_UINT", "RGBA16_SINT", "RGBA16_SNORM",
                 "RGBA16F", "RGBA16_FLOAT" -> 8.0f;

            // ---- 12 bytes/pixel ----
            case "RGB32F", "RGB32_FLOAT", "RGB32_UINT", "RGB32_SINT" -> 12.0f;

            // ---- 16 bytes/pixel ----
            case "RGBA32F", "RGBA32_FLOAT", "RGBA32_UINT", "RGBA32_SINT" -> 16.0f;

            // ---- Compressed (approximate) ----
            case "BC1_RGB_UNORM_BLOCK", "BC1_RGB_SRGB_BLOCK", "BC1_RGBA_UNORM_BLOCK", "BC1_RGBA_SRGB_BLOCK",
                 "BC4_UNORM_BLOCK", "BC4_SNORM_BLOCK", "COMPRESSED_RED_RGTC1", "COMPRESSED_SIGNED_RED_RGTC1" -> 0.5f;
            case "BC2_UNORM_BLOCK", "BC2_SRGB_BLOCK", "BC3_UNORM_BLOCK", "BC3_SRGB_BLOCK",
                 "BC5_UNORM_BLOCK", "BC5_SNORM_BLOCK", "BC6H_UFLOAT_BLOCK", "BC6H_SFLOAT_BLOCK",
                 "BC7_UNORM_BLOCK", "BC7_SRGB_BLOCK", "COMPRESSED_RG_RGTC2", "COMPRESSED_SIGNED_RG_RGTC2" -> 1.0f;

            default -> 4.0f; // conservative fallback (RGBA8 equivalent)
        };
    }

    /**
     * OpenGL sized internal format 常量 → 每像素字节数。未知格式保守按 4 bpp 计。
     */
    public static float lookup(int glInternalFormat) {
        return switch (glInternalFormat) {
            // ---- 1 byte/pixel ----
            case 0x8229 /*GL_R8*/,
                 0x8F94 /*GL_R8_SNORM*/,
                 0x8231 /*GL_R8I*/,
                 0x8232 /*GL_R8UI*/ -> 1.0f;

            // ---- 2 bytes/pixel ----
            case 0x822A /*GL_R16*/,
                 0x8F98 /*GL_R16_SNORM*/,
                 0x822D /*GL_R16F*/,
                 0x8233 /*GL_R16I*/,
                 0x8234 /*GL_R16UI*/,
                 0x822B /*GL_RG8*/,
                 0x8F95 /*GL_RG8_SNORM*/,
                 0x8237 /*GL_RG8I*/,
                 0x8238 /*GL_RG8UI*/,
                 0x81A5 /*GL_DEPTH_COMPONENT16*/,
                 0x8056 /*GL_RGBA4*/,
                 0x8057 /*GL_RGB5_A1*/,
                 0x8D62 /*GL_RGB565*/ -> 2.0f;

            // ---- 3 bytes/pixel ----
            case 0x8051 /*GL_RGB8*/,
                 0x8F96 /*GL_RGB8_SNORM*/,
                 0x8D8F /*GL_RGB8I*/,
                 0x8D7D /*GL_RGB8UI*/,
                 0x81A6 /*GL_DEPTH_COMPONENT24*/ -> 3.0f;

            // ---- 4 bytes/pixel ----
            case 0x8058 /*GL_RGBA8*/,
                 0x8F97 /*GL_RGBA8_SNORM*/,
                 0x8D8E /*GL_RGBA8I*/,
                 0x8D7C /*GL_RGBA8UI*/,
                 0x822E /*GL_R32F*/,
                 0x8235 /*GL_R32I*/,
                 0x8236 /*GL_R32UI*/,
                 0x822F /*GL_RG16F*/,
                 0x8239 /*GL_RG16I*/,
                 0x823A /*GL_RG16UI*/,
                 0x81A7 /*GL_DEPTH_COMPONENT32*/,
                 0x8CAC /*GL_DEPTH_COMPONENT32F*/,
                 0x88F0 /*GL_DEPTH24_STENCIL8*/,
                 0x8C3A /*GL_R11F_G11F_B10F*/,
                 0x8C3D /*GL_RGB9_E5*/,
                 0x8059 /*GL_RGB10_A2*/,
                 0x906F /*GL_RGB10_A2UI*/ -> 4.0f;

            // ---- 5 bytes/pixel ----
            case 0x8CAD /*GL_DEPTH32F_STENCIL8*/ -> 5.0f;

            // ---- 6 bytes/pixel ----
            case 0x8054 /*GL_RGB16*/,
                 0x8F9A /*GL_RGB16_SNORM*/,
                 0x881B /*GL_RGB16F*/,
                 0x8D89 /*GL_RGB16I*/,
                 0x8D77 /*GL_RGB16UI*/ -> 6.0f;

            // ---- 8 bytes/pixel ----
            case 0x8230 /*GL_RG32F*/,
                 0x823B /*GL_RG32I*/,
                 0x823C /*GL_RG32UI*/,
                 0x805B /*GL_RGBA16*/,
                 0x8F9B /*GL_RGBA16_SNORM*/,
                 0x881A /*GL_RGBA16F*/,
                 0x8D88 /*GL_RGBA16I*/,
                 0x8D76 /*GL_RGBA16UI*/ -> 8.0f;

            // ---- 12 bytes/pixel ----
            case 0x8815 /*GL_RGB32F*/,
                 0x8D83 /*GL_RGB32I*/,
                 0x8D71 /*GL_RGB32UI*/ -> 12.0f;

            // ---- 16 bytes/pixel ----
            case 0x8814 /*GL_RGBA32F*/,
                 0x8D82 /*GL_RGBA32I*/,
                 0x8D70 /*GL_RGBA32UI*/ -> 16.0f;

            // ---- S3TC / RGTC / BPTC compressed (approximate average) ----
            case 0x83F0 /*GL_COMPRESSED_RGB_S3TC_DXT1_EXT*/,
                 0x83F1 /*GL_COMPRESSED_RGBA_S3TC_DXT1_EXT*/,
                 0x8DBB /*GL_COMPRESSED_RED_RGTC1*/,
                 0x8DBC /*GL_COMPRESSED_SIGNED_RED_RGTC1*/ -> 0.5f;
            case 0x83F2 /*GL_COMPRESSED_RGBA_S3TC_DXT3_EXT*/,
                 0x83F3 /*GL_COMPRESSED_RGBA_S3TC_DXT5_EXT*/,
                 0x8DBD /*GL_COMPRESSED_RG_RGTC2*/,
                 0x8DBE /*GL_COMPRESSED_SIGNED_RG_RGTC2*/,
                 0x8E8E /*GL_COMPRESSED_RGB_BPTC_SIGNED_FLOAT*/,
                 0x8E8F /*GL_COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT*/,
                 0x8E8C /*GL_COMPRESSED_RGBA_BPTC_UNORM*/,
                 0x8E8D /*GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM*/ -> 1.0f;

            // ---- Buffer objects: format=0 means raw bytes (1 byte per "pixel") ----
            case 0 -> 1.0f;

            default -> 4.0f; // conservative fallback (RGBA8 equivalent)
        };
    }
}
