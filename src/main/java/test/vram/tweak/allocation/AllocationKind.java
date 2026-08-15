package test.vram.tweak.allocation;

/**
 * GL 对象命名空间类型。OpenGL 中纹理与缓冲是两套独立的 ID 命名空间，
 * tex=1 与 buf=1 可以同时存在，因此追踪器必须带 kind 作为复合键。
 */
public enum AllocationKind {
    TEXTURE,
    BUFFER;

    /** 复合键：kind + GL 对象 ID。 */
    public record Key(AllocationKind kind, int glId) {}
}
