package test.vram.tweak.allocation;

/**
 * A single GPU allocation record with lifecycle tracking.
 *
 * <p>Created at Blaze3D-layer allocation points (GpuDevice.createTexture /
 * BufferStorage.createBuffer) and marked freed at the matching close/destroy
 * points. All sizes are estimated bytes — see {@link TextureFormatBytes}.</p>
 */
public final class VramAllocationRecord {

    // ---- Identity ----
    private final AllocationKind kind;
    private final int glObjectId;

    // ---- Dimensions ----
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final String formatName;
    private long estimatedBytes;

    // ---- Classification ----
    private volatile AllocationCategory category;
    private volatile SourceTag source;
    private String label;

    // ---- Lifecycle ----
    private final long allocTick;        // Minecraft tick when allocated
    private final long allocTimeMs;      // System time when allocated
    private volatile long freeTick;      // Minecraft tick when freed (-1 if alive)
    private volatile long freeTimeMs;    // System time when freed
    private volatile boolean alive;

    // ---- Origin ----
    private String callerClass;          // simplified caller class name

    /**
     * Creates a new allocation record.
     *
     * @param kind           texture or buffer namespace
     * @param glObjectId     OpenGL object ID (0 if unknown / non-GL backend)
     * @param width          texture width in pixels (0 for buffers)
     * @param height         texture height in pixels (0 for buffers)
     * @param depth          texture depth/layers (1 for 2D, 6 for cubemaps, 1 for buffers)
     * @param mipLevels      number of mip levels (1 = no extra mips)
     * @param bytesPerPixel  bytes per pixel for the format (1 for buffers)
     * @param formatName     Blaze3D GpuFormat name, or null for buffers
     * @param label          optional label from Blaze3D createTexture/createBuffer
     * @param allocTick      game tick when allocated
     * @param callerClass    simplified calling class name
     */
    public VramAllocationRecord(AllocationKind kind, int glObjectId, int width, int height, int depth,
                                 int mipLevels, float bytesPerPixel, String formatName,
                                 String label, long allocTick, String callerClass) {
        this.kind = kind;
        this.glObjectId = glObjectId;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = Math.max(1, mipLevels);
        this.formatName = formatName;
        this.label = (label != null && !label.isEmpty()) ? label : null;

        // Estimate VRAM usage
        float mipFactor = this.mipLevels > 1 ? mipmapTotalFactor(this.mipLevels) : 1.0f;
        this.estimatedBytes = (long) (width * (long) height * depth * bytesPerPixel * mipFactor);

        // Classify
        this.source = SourceTag.fromLabel(label);
        this.category = classifyFromSource(this.source);

        this.allocTick = allocTick;
        this.allocTimeMs = System.currentTimeMillis();
        this.freeTick = -1;
        this.freeTimeMs = -1;
        this.alive = true;
        this.callerClass = callerClass;
    }

    // ---- Lifecycle markers ----

    /** Marks this allocation as freed/deleted. */
    public void markFreed(long freeTick) {
        this.alive = false;
        this.freeTick = freeTick;
        this.freeTimeMs = System.currentTimeMillis();
    }

    /** Promotes this record to a render-target category (called from D-layer hook). */
    public void markAsRenderTarget(AllocationCategory rtCategory) {
        markAsRenderTarget(rtCategory, SourceTag.UNKNOWN_SOURCE);
    }

    /** Promotes this record to a render-target category with source hint. */
    public void markAsRenderTarget(AllocationCategory rtCategory, SourceTag rtSource) {
        if (rtCategory == null || !rtCategory.isRenderTarget()) return;

        // Bug-fix: do NOT override atlas/texture categories.
        // Atlas textures (e.g. shield_patterns.png 6144×4096) may be temporarily
        // attached to framebuffers by Minecraft's composition pipeline, but they
        // are NOT render targets — they're regular textures used as FBO inputs.
        boolean isAtlas = this.category == AllocationCategory.TEXTURE_ATLAS
                       || this.category == AllocationCategory.TEXTURE_BLOCK
                       || this.category == AllocationCategory.TEXTURE_ENTITY
                       || this.category == AllocationCategory.TEXTURE_ITEM
                       || this.category == AllocationCategory.TEXTURE_ENVIRONMENT
                       || this.category == AllocationCategory.TEXTURE_FONT
                       || this.category == AllocationCategory.TEXTURE_GUI
                       || this.category == AllocationCategory.TEXTURE_PAINTING
                       || this.category == AllocationCategory.TEXTURE_MISC;
        if (isAtlas) return;

        boolean wasUnknown = this.category == AllocationCategory.UNKNOWN;
        this.category = rtCategory;

        // D-layer has better context: always trust its source hint.
        // Only preserve A-layer source when D-layer source is unknown.
        if (rtSource != SourceTag.UNKNOWN_SOURCE) {
            this.source = rtSource;
        } else if (!wasUnknown) {
            // Keep existing source (A-layer label was more specific)
        }
    }

    // ---- Getters ----

    public AllocationKind getKind() { return kind; }
    public int getGlObjectId() { return glObjectId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public int getMipLevels() { return mipLevels; }
    public String getFormatName() { return formatName; }
    public long getEstimatedBytes() { return estimatedBytes; }
    public void setEstimatedBytes(long bytes) { this.estimatedBytes = bytes; }
    public AllocationCategory getCategory() { return category; }
    public void setCategory(AllocationCategory cat) { this.category = cat; }
    public SourceTag getSource() { return source; }
    public String getLabel() { return label; }
    public long getAllocTick() { return allocTick; }
    public long getAllocTimeMs() { return allocTimeMs; }
    public long getFreeTick() { return freeTick; }
    public long getFreeTimeMs() { return freeTimeMs; }
    public boolean isAlive() { return alive; }
    public String getCallerClass() { return callerClass; }

    /** Duration in milliseconds (current time minus alloc time if alive, free time minus alloc time if dead). */
    public long getLifetimeMs() {
        long end = alive ? System.currentTimeMillis() : freeTimeMs;
        return end - allocTimeMs;
    }

    // ---- Internal classification ----

    private static AllocationCategory classifyFromSource(SourceTag source) {
        return switch (source) {
            case ATLAS_BLOCKS, ATLAS_ITEMS, ATLAS_BANNER,
                 ATLAS_SHIELD, ATLAS_PAINTING, ATLAS_MISC -> AllocationCategory.TEXTURE_ATLAS;
            case SODIUM_TERRAIN -> AllocationCategory.TEXTURE_BLOCK;
            case IRIS_GBUFFER -> AllocationCategory.RENDER_TARGET_COLOR;
            case IRIS_SHADOW -> AllocationCategory.RENDER_TARGET_SHADOW;
            case IRIS_COMPOSITE -> AllocationCategory.RENDER_TARGET_COLOR;
            case IRIS_TEMPORAL -> AllocationCategory.RENDER_TARGET_COLOR;
            case MC_SKYBOX -> AllocationCategory.TEXTURE_ENVIRONMENT;
            case MC_ENTITY -> AllocationCategory.TEXTURE_ENTITY;
            case MC_FONT -> AllocationCategory.TEXTURE_FONT;
            case MC_GUI -> AllocationCategory.TEXTURE_GUI;
            case MC_PARTICLE -> AllocationCategory.TEXTURE_MISC;
            case UNKNOWN_SOURCE -> AllocationCategory.UNKNOWN;
        };
    }

    /**
     * Geometric series factor for mipmap levels.
     * Total bytes ≈ original × (1 + 1/4 + 1/16 + ... ) = original × 4/3 in the limit.
     */
    private static float mipmapTotalFactor(int levels) {
        float sum = 0f;
        float factor = 1f;
        for (int i = 0; i < levels; i++) {
            sum += factor;
            factor *= 0.25f;
        }
        return sum;
    }

    @Override
    public String toString() {
        return String.format("VramAlloc{%s id=%d, %s/%s, %d×%d, %d MB, %s}",
                kind, glObjectId, category, source, width, height,
                estimatedBytes / (1024 * 1024), alive ? "alive" : "freed");
    }
}
