package test.vram.tweak.eviction;

/**
 * A texture that has been evicted from VRAM to system RAM.
 *
 * <p>When VRAM usage exceeds the threshold, the eviction manager replaces
 * full-size textures with 1×1 placeholders via {@code glTexImage2D},
 * freeing the VRAM. The original pixel data is retained in this record
 * for later restoration.</p>
 *
 * <p>Restoration happens reactively — when the texture is bound again
 * via {@code _bindTexture}, the eviction manager re-uploads the pixel
 * data from this record.</p>
 */
public final class VramEvictionRecord {

    private final int glObjectId;
    private final byte[] pixelData;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int internalformat;
    private final int format;
    private final int type;
    private final long estimatedBytesSaved;
    private final String label;
    private final long evictTimeMs;
    private volatile long restoreTimeMs;
    private volatile boolean restored;

    public VramEvictionRecord(int glObjectId, byte[] pixelData,
                               int width, int height, int depth,
                               int mipLevels, int internalformat,
                               int format, int type,
                               long estimatedBytesSaved, String label) {
        this.glObjectId = glObjectId;
        this.pixelData = pixelData;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.internalformat = internalformat;
        this.format = format;
        this.type = type;
        this.estimatedBytesSaved = estimatedBytesSaved;
        this.label = label;
        this.evictTimeMs = System.currentTimeMillis();
        this.restoreTimeMs = -1;
        this.restored = false;
    }

    public int getGlObjectId() { return glObjectId; }
    public byte[] getPixelData() { return pixelData; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public int getMipLevels() { return mipLevels; }
    public int getInternalformat() { return internalformat; }
    public int getFormat() { return format; }
    public int getType() { return type; }
    public long getEstimatedBytesSaved() { return estimatedBytesSaved; }
    public String getLabel() { return label; }
    public long getEvictTimeMs() { return evictTimeMs; }
    public long getRestoreTimeMs() { return restoreTimeMs; }
    public boolean isRestored() { return restored; }

    public void markRestored() {
        this.restored = true;
        this.restoreTimeMs = System.currentTimeMillis();
    }

    /** Eviction age in seconds. */
    public long getAgeSeconds() {
        return (System.currentTimeMillis() - evictTimeMs) / 1000;
    }

    @Override
    public String toString() {
        return String.format("Evict{id=%d, %dx%d, %dMB, label=%s, age=%ds}",
                glObjectId, width, height,
                estimatedBytesSaved / (1024 * 1024),
                label != null ? label : "?",
                getAgeSeconds());
    }
}