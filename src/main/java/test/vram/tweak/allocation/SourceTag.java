package test.vram.tweak.allocation;

/**
 * Identifies which subsystem or mod produced a GPU allocation.
 *
 * <p>Derived from the texture label (via {@link #fromLabel}) or from the
 * framebuffer-attachment context (via the D-layer hook).</p>
 *
 * @see AllocationCategory
 */
public enum SourceTag {

    // --- Minecraft atlases ---
    ATLAS_BLOCKS,
    ATLAS_ITEMS,
    ATLAS_BANNER,
    ATLAS_SHIELD,
    ATLAS_PAINTING,
    ATLAS_MISC,

    // --- Sodium ---
    SODIUM_TERRAIN,

    // --- Iris shader render targets ---
    IRIS_GBUFFER,
    IRIS_SHADOW,
    IRIS_COMPOSITE,
    IRIS_TEMPORAL,

    // --- Minecraft subsystems ---
    MC_SKYBOX,
    MC_ENTITY,
    MC_FONT,
    MC_GUI,
    MC_PARTICLE,

    // --- Unknown ---
    UNKNOWN_SOURCE;

    /**
     * Heuristic classification from texture label string.
     *
     * <p>Labels come from Blaze3D's {@code Supplier<String>} parameter in
     * {@code GpuDevice.createTexture()}.</p>
     */
    public static SourceTag fromLabel(String label) {
        if (label == null || label.isEmpty()) return UNKNOWN_SOURCE;

        String lower = label.toLowerCase();

        if (lower.contains("block_atlas") || lower.contains("block")) return ATLAS_BLOCKS;
        if (lower.contains("item_atlas") || lower.contains("item")) return ATLAS_ITEMS;
        if (lower.contains("banner")) return ATLAS_BANNER;
        if (lower.contains("shield")) return ATLAS_SHIELD;
        if (lower.contains("paint") || lower.contains("painting")) return ATLAS_PAINTING;
        if (lower.contains("atlas")) return ATLAS_MISC;

        if (lower.contains("terrain") || lower.contains("chunk") || lower.contains("region"))
            return SODIUM_TERRAIN;

        if (lower.contains("gbuffer") || lower.contains("g-buffer")
                || lower.contains("colortex") || lower.contains("color"))
            return IRIS_GBUFFER;
        if (lower.contains("shadow") || lower.contains("shadowcolor"))
            return IRIS_SHADOW;
        if (lower.contains("composite") || lower.contains("composite"))
            return IRIS_COMPOSITE;
        if (lower.contains("temporal") || lower.contains("prev"))
            return IRIS_TEMPORAL;

        if (lower.contains("sky") || lower.contains("cloud") || lower.contains("sun")
                || lower.contains("moon") || lower.contains("weather"))
            return MC_SKYBOX;
        if (lower.contains("entity") || lower.contains("skin") || lower.contains("player"))
            return MC_ENTITY;
        if (lower.contains("font") || lower.contains("glyph"))
            return MC_FONT;
        if (lower.contains("gui") || lower.contains("hud") || lower.contains("widget"))
            return MC_GUI;
        if (lower.contains("particle"))
            return MC_PARTICLE;

        return UNKNOWN_SOURCE;
    }
}
