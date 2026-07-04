package test.vram.tweak.client.mixin;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Single choke-point for ALL GPU texture creation in Blaze3D.
 *
 * @ModifyVariable handlers fire in PARAMETER ORDER (not Inject order).
 * Execution: Supplier → int usage → TextureFormat → int width → int height → depth → mipLevels.
 * This is why we capture IS_ATLAS in the Supplier handler (param 1, fires first),
 * not in an @Inject which would fire AFTER all @ModifyVariable handlers.
 */
@Mixin(GlDevice.class)
public class MixinGpuDevice_VRAMOptimize {

    private static final ThreadLocal<String> CURRENT_FORMAT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> STORED_WIDTH = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IS_ATLAS = new ThreadLocal<>();
    private static final ThreadLocal<String> ATLAS_NAME = new ThreadLocal<>();

    // Format trace (diagnostic: log first N distinct GpuFormats seen)
    private static final java.util.Set<String> FORMATS_SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static int formatTraceCount;
    private static final int FORMAT_TRACE_MAX = 50;

    // ---- Param 1: Supplier<String> label (fires FIRST) ----

    @ModifyVariable(
        method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)"
                + "Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private Supplier<String> captureLabel(Supplier<String> label) {
        try {
            String name = label.get();
            boolean isAtlas = name != null && name.contains("atlas");
            IS_ATLAS.set(isAtlas);
            ATLAS_NAME.set(isAtlas ? name : null);
        } catch (Exception e) {
            IS_ATLAS.set(false);
            ATLAS_NAME.set(null);
        }
        return label;
    }

    // ---- Param 3: GpuFormat format ----

    @ModifyVariable(
        method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)"
                + "Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private TextureFormat downscaleFormat(TextureFormat fmt) {
        CURRENT_FORMAT.set(fmt.name());
        // Passive format trace — log each distinct format once (first 50)
        if (formatTraceCount < FORMAT_TRACE_MAX) {
            String name = fmt.name();
            if (FORMATS_SEEN.add(name)) {
                VRAMTweak.LOGGER.info("[FormatTrace] GlDevice.createTexture format: {} (#{})",
                        name, FORMATS_SEEN.size());
            }
            formatTraceCount++;
            if (formatTraceCount == FORMAT_TRACE_MAX) {
                VRAMTweak.LOGGER.info("[FormatTrace] Limit reached ({}). Seen {} distinct formats.",
                        FORMAT_TRACE_MAX, FORMATS_SEEN.size());
            }
        }
        try {
            if (VRAMOptimizer.shouldDownscaleFormat(fmt.name())) {
                VRAMOptimizer.logDownscale("GlDevice", fmt.name());
                return TextureFormat.RGBA8;
            }
            if (VRAMOptimizer.shouldDownscaleDepth(fmt.name())) {
                VRAMOptimizer.logDepthDownscale(fmt.name());
                return TextureFormat.DEPTH32;
            }
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("downscaleFormat failed", e);
        }
        return fmt;
    }

    // ---- Param 4: int width ----

    @ModifyVariable(method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)"
            + "Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private int capWidth(int w) {
        STORED_WIDTH.set(w);
        try {
            if (Boolean.TRUE.equals(IS_ATLAS.get())) {
                var cfg = VRAMConfig.getInstance().texture;
                if (cfg.atlasSizeLimit && w > cfg.maxAtlasSize) {
                    String name = ATLAS_NAME.get();
                    if (name != null) {
                        VRAMOptimizer.logAtlasCap(name, "W", w, cfg.maxAtlasSize);
                    }
                    STORED_WIDTH.set(cfg.maxAtlasSize);
                    return cfg.maxAtlasSize;
                }
            }
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("capWidth failed", e);
        }
        return w;
    }

    // ---- Param 5: int height ----

    @ModifyVariable(method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)"
            + "Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private int capHeight(int h) {
        try {
            if (Boolean.TRUE.equals(IS_ATLAS.get())) {
                String name = ATLAS_NAME.get();
                Integer sw = STORED_WIDTH.get();
                String fmt = CURRENT_FORMAT.get();
                // Log every atlas creation (only ~3-5 per session)
                if (name != null && sw != null && fmt != null) {
                    VRAMOptimizer.logAtlasTracked(name, sw, h, fmt);
                }
                var cfg = VRAMConfig.getInstance().texture;
                if (cfg.atlasSizeLimit && h > cfg.maxAtlasSize) {
                    if (name != null) {
                        VRAMOptimizer.logAtlasCap(name, "H", h, cfg.maxAtlasSize);
                    }
                    return cfg.maxAtlasSize;
                }
                return h;
            }
            Integer sw = STORED_WIDTH.get();
            String fmt = CURRENT_FORMAT.get();
            if (sw != null && VRAMOptimizer.isDepthFormat(fmt) && VRAMOptimizer.shouldCap(sw, h)) {
                int capped = VRAMOptimizer.capSize(h);
                VRAMOptimizer.logShadowCap(sw, h, sw, capped);
                return capped;
            }
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("capHeight failed", e);
        }
        return h;
    }

    // ---- Metrics ----

    @Inject(method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)"
            + "Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"))
    private void onTextureCreated(Supplier<String> label, int usage, TextureFormat format,
            int width, int height, int depth, int mipLevels,
            CallbackInfoReturnable<GpuTexture> cir) {
        MetricsEngine.textureAllocations.incrementAndGet();
    }
}
