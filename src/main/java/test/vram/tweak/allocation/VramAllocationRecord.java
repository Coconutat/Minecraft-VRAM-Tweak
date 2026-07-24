package test.vram.tweak.allocation;

/**
 * A single GPU texture allocation record with lifecycle tracking.
 *
 * <p>Created when a texture is allocated (via {@code _texImage2D} hook),
 * updated when it is deleted (via {@code _deleteTexture} hook).</p>
 *
 * <p>All sizes in estimated bytes — see {@link TextureFormatBytes} for the
 * format-to-bytes-per-pixel mapping used for estimation.</p>
 */
public final class VramAllocationRecord {

    // ---- GL identity ----
    private final int glObjectId;

    // ---- Dimensions ----
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int glInternalFormat;
    private final long estimatedBytes;

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
     * Creates a new allocation record for a texture.
     *
     * @param glObjectId      OpenGL texture/buffer object ID
     * @param width           texture width in pixels
     * @param height          texture height in pixels
     * @param depth           texture depth (1 for 2D, &gt;1 for 3D)
     * @param mipLevels       number of mipmap levels (0 means no mipmaps)
     * @param glInternalFormat OpenGL internal format constant (e.g. GL_RGBA8)
     * @param label           optional label from Blaze3D createTexture supplier
     * @param allocTick       game tick when allocated
     * @param callerClass     simplified calling class name
     */
    public VramAllocationRecord(int glObjectId, int width, int height, int depth,
                                 int mipLevels, int glInternalFormat,
                                 String label, long allocTick, String callerClass) {
        this.glObjectId = glObjectId;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = Math.max(0, mipLevels);
        this.glInternalFormat = glInternalFormat;
        this.label = (label != null && !label.isEmpty()) ? label : null;

        // Estimate VRAM usage
        float bytesPerPixel = TextureFormatBytes.lookup(glInternalFormat);
        float mipFactor = this.mipLevels > 1 ? mipmapTotalFactor(this.mipLevels) : 1.0f;
        this.estimatedBytes = (long) (width * height * depth * bytesPerPixel * mipFactor);

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
        if (rtCategory != null && rtCategory.isRenderTarget()) {
            this.category = rtCategory;
        }
    }

    // ---- Getters ----

    public int getGlObjectId() { return glObjectId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public int getMipLevels() { return mipLevels; }
    public int getGlInternalFormat() { return glInternalFormat; }
    public long getEstimatedBytes() { return estimatedBytes; }
    public AllocationCategory getCategory() { return category; }
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
        return String.format("VramAlloc{id=%d, %s/%s, %d×%d, %d MB, %s}",
                glObjectId, category, source, width, height,
                estimatedBytes / (1024 * 1024), alive ? "alive" : "freed");
    }
}
