package test.vram.tweak.compression;

/**
 * Texture category classifier based on debug label/name.
 *
 * Determines S3TC compression eligibility per category.
 * All categories default to OFF — user enables individually.
 */
public enum TextureCategory {
    BLOCK_ATLAS,    // "atlas" in name, not containing "_n." or "_s."
    ENTITY,         // "entity/" or "mob/" in name
    GUI,            // "gui/" or "widget" in name
    FONT,           // "font" or "glyph" in name
    PBR_ATLAS,      // "atlas" + "_n." or "_s." (normal/specular)
    PARTICLE,       // "particle" in name
    LIGHT_MAP,      // very small (≤32×32)
    DEPTH,          // format starts with "D" (depth/stencil)
    SHADOW,         // "Shadow" or "shadow" in name
    OTHER;          // unknown/uncategorized

    /**
     * Classify a texture by its debug label, format, and dimensions.
     * Called at createTexture time (label available).
     */
    public static TextureCategory classify(String label, String formatName, int width, int height) {
        if (label == null) return OTHER;

        // Check depth format first
        if (formatName != null && (formatName.startsWith("D") || formatName.startsWith("S"))) {
            if (label.contains("Shadow") || label.contains("shadow")) return SHADOW;
            if (label.startsWith("Depth") || label.contains("Depth")) return DEPTH;
            return DEPTH; // any depth format = not compressible
        }

        // Check atlas first (most common + largest impact)
        if (label.contains("atlas")) {
            if (label.contains("_n.") || label.contains("_s.")) return PBR_ATLAS;
            return BLOCK_ATLAS;
        }

        // Specific path patterns
        if (label.contains("font") || label.contains("glyph")) return FONT;
        if (label.contains("gui/") || label.contains("widget")) return GUI;
        if (label.contains("entity/") || label.contains("mob/")) return ENTITY;
        if (label.contains("particle")) return PARTICLE;

        // Light map: very small textures
        if (width <= 32 && height <= 32 && (label.contains("light") || label.contains("Light"))) {
            return LIGHT_MAP;
        }

        return OTHER;
    }

    /** Can this category be S3TC compressed? Depends on user config. */
    public boolean isCompressible(S3TCConfig cfg) {
        return switch (this) {
            case BLOCK_ATLAS -> cfg.compressBlockAtlas;
            case ENTITY -> cfg.compressEntityTextures;
            case GUI -> cfg.compressGuiTextures;
            case FONT, PBR_ATLAS, PARTICLE, LIGHT_MAP, DEPTH, SHADOW -> false;
            case OTHER -> cfg.compressOther;
        };
    }

    /** Minimum dimension threshold for compression (skip tiny textures). */
    public static boolean meetsSizeThreshold(int width, int height) {
        return width >= 256 && height >= 256;
    }
}
