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

        // ---- Atlas subtypes (specific first, then broad) ----
        if (lower.contains("block_atlas")) return ATLAS_BLOCKS;
        if (lower.contains("item_atlas")) return ATLAS_ITEMS;

        // Sodium sprite/terrain atlases
        if (lower.contains("sodium")) return SODIUM_TERRAIN;

        // Broad atlas type heuristics (label may just be "blocks", "items" etc.)
        if (lower.contains("block") && lower.contains("atlas")) return ATLAS_BLOCKS;
        if (lower.contains("item") && lower.contains("atlas")) return ATLAS_ITEMS;
        if (lower.equals("blocks") || lower.equals("block")) return ATLAS_BLOCKS;
        if (lower.equals("items") || lower.equals("item")) return ATLAS_ITEMS;

        // Named atlas subtypes
        if (lower.contains("banner")) return ATLAS_BANNER;
        if (lower.contains("shield")) return ATLAS_SHIELD;
        if (lower.contains("paint") || lower.contains("painting")) return ATLAS_PAINTING;
        if (lower.contains("chest")) return ATLAS_MISC;
        if (lower.contains("bed")) return ATLAS_MISC;
        if (lower.contains("sign")) return ATLAS_MISC;
        if (lower.contains("shulker")) return ATLAS_MISC;
        if (lower.contains("bell")) return ATLAS_MISC;
        if (lower.contains("decorated_pot")) return ATLAS_MISC;
        if (lower.contains("effect")) return ATLAS_MISC;
        if (lower.contains("map")) return ATLAS_MISC;
        if (lower.contains("trim")) return ATLAS_MISC;

        // Generic atlas catch-all (must be after specific checks)
        if (lower.contains("atlas")) return ATLAS_MISC;

        // ---- Sodium / terrain ----
        if (lower.contains("terrain") || lower.contains("chunk") || lower.contains("region")
                || lower.contains("sodium"))
            return SODIUM_TERRAIN;

        // ---- Iris shader render targets ----
        if (lower.contains("gbuffer") || lower.contains("g-buffer")
                || lower.contains("colortex") || lower.contains("color"))
            return IRIS_GBUFFER;
        if (lower.contains("shadow") || lower.contains("shadowcolor"))
            return IRIS_SHADOW;
        if (lower.contains("composite") || lower.contains("composite"))
            return IRIS_COMPOSITE;
        if (lower.contains("temporal") || lower.contains("prev"))
            return IRIS_TEMPORAL;

        // ---- Minecraft subsystems ----
        if (lower.contains("sky") || lower.contains("cloud") || lower.contains("sun")
                || lower.contains("moon") || lower.contains("weather"))
            return MC_SKYBOX;
        if (lower.contains("entity") || lower.contains("skin") || lower.contains("player")
                || lower.contains("mob") || lower.contains("horse") || lower.contains("wolf"))
            return MC_ENTITY;
        if (lower.contains("font") || lower.contains("glyph"))
            return MC_FONT;
        if (lower.contains("gui") || lower.contains("hud") || lower.contains("widget")
                || lower.contains("container") || lower.contains("inventory"))
            return MC_GUI;
        if (lower.contains("particle"))
            return MC_PARTICLE;

        return UNKNOWN_SOURCE;
    }

    /** Human-readable name for command/HUD output. */
    public String getDisplayName() {
        return switch (this) {
            case ATLAS_BLOCKS -> "BlocksAtlas";
            case ATLAS_ITEMS -> "ItemsAtlas";
            case ATLAS_BANNER -> "BannerAtlas";
            case ATLAS_SHIELD -> "ShieldAtlas";
            case ATLAS_PAINTING -> "PaintAtlas";
            case ATLAS_MISC -> "MiscAtlas";
            case SODIUM_TERRAIN -> "SodiumTerrain";
            case IRIS_GBUFFER -> "IrisGBuffer";
            case IRIS_SHADOW -> "IrisShadow";
            case IRIS_COMPOSITE -> "IrisComposite";
            case IRIS_TEMPORAL -> "IrisTemporal";
            case MC_SKYBOX -> "Skybox";
            case MC_ENTITY -> "Entity";
            case MC_FONT -> "Font";
            case MC_GUI -> "GUI";
            case MC_PARTICLE -> "Particle";
            case UNKNOWN_SOURCE -> "?";
        };
    }
}
