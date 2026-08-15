package test.vram.tweak.gpu;

/**
 * VRAM 查询抽象。GL 后端实现见 {@link GlVramProbe}；未来 Vulkan 后端提供新实现。
 * 返回 KB；失败返回 -1（freeKB）或 0（totalKB）。
 */
public interface VramProbe {
    /** 当前空闲显存（KB），失败返回 -1。 */
    long freeKB();

    /** 总显存（KB），失败返回 0。 */
    long totalKB();

    /** 数据来源描述（用于诊断）。 */
    String source();
}
