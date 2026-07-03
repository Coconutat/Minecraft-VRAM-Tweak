package test.vram.tweak.compression;

/**
 * Texture category classifier based on debug label/name.
 *
 * Determines S3TC compression eligibility per category.
 * All categories default to OFF — user enables individually.
 */
public enum TextureCategory {
    BLOCK_ATLAS, ENTITY, GUI, FONT, PBR_ATLAS, PARTICLE, LIGHT_MAP, DEPTH, SHADOW, OTHER;

    public static TextureCategory classify(String label, String formatName, int width, int height) {
        if (label == null) return OTHER;
        if (formatName != null && (formatName.startsWith("D") || formatName.startsWith("S"))) {
            if (label.contains("Shadow") || label.contains("shadow")) return SHADOW;
            if (label.startsWith("Depth") || label.contains("Depth")) return DEPTH;
            return DEPTH;
        }
        if (label.contains("atlas")) {
            if (label.contains("_n.") || label.contains("_s.")) return PBR_ATLAS;
            return BLOCK_ATLAS;
        }
        if (label.contains("font") || label.contains("glyph")) return FONT;
        if (label.contains("gui/") || label.contains("widget")) return GUI;
        if (label.contains("entity/") || label.contains("mob/")) return ENTITY;
        if (label.contains("particle")) return PARTICLE;
        if (width <= 32 && height <= 32 && (label.contains("light") || label.contains("Light"))) return LIGHT_MAP;
        return OTHER;
    }

    public boolean isCompressible(S3TCConfig cfg) {
        return switch (this) {
            case BLOCK_ATLAS -> cfg.compressBlockAtlas;
            case ENTITY -> cfg.compressEntityTextures;
            case GUI -> cfg.compressGuiTextures;
            case FONT, PBR_ATLAS, PARTICLE, LIGHT_MAP, DEPTH, SHADOW -> false;
            case OTHER -> cfg.compressOther;
        };
    }

    public static boolean meetsSizeThreshold(int width, int height) {
        return width >= 256 && height >= 256;
    }
}
