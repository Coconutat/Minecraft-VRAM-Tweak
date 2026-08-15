package test.vram.tweak.gui;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.allocation.VramAllocationTracker;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.gpu.GPUDetector;
import test.vram.tweak.vram.VRAMGovernor;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Cloth Config GUI — 4 类布局: VRAM / 纹理 / HUD / 诊断.
 *
 * <p>从 7 类合并而来：调速器并入 VRAM，实验性并入诊断，
 * HUD 内部按性能/显存/高级分组。</p>
 */
public class ClothConfigFactory {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/gui");
    private static final Component NEEDS_RESTART =
            Component.literal(" ⚠").append(Component.translatable("vramtweak.gui.needsRestart"));

    public static Screen create(Screen parent) {
        LOG.info("[GUI] Config screen opened");
        var builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("vramtweak.gui.title"))
                .setSavingRunnable(() -> {
                    LOG.info("[GUI] Saving config");
                    VRAMConfig.save();
                    VRAMOptimizer.reload();
                    VRAMGovernor.reload();
                });

        var eb = builder.entryBuilder();
        var cfg = VRAMConfig.getInstance();

        // ================================================================
        // 1. VRAM 优化（含调速器 + 实验性）
        // ================================================================
        var vram = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.vram"));

        // -- 主开关 --
        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.vram.enabled"),
                        cfg.vram.enabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.vram.enabled.tooltip"))
                .setSaveConsumer(v -> { cfg.vram.enabled = v; VRAMOptimizer.reload(); })
                .build());

        // -- 阴影 --
        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.shadowCapEnabled"),
                        cfg.vram.shadowCapEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.shadowCapEnabled.tooltip"))
                .setRequirement(() -> cfg.vram.enabled)
                .setSaveConsumer(v -> { cfg.vram.shadowCapEnabled = v; })
                .build());

        vram.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.shadowMapMaxSize"),
                        cfg.vram.shadowMapMaxSize)
                .setDefaultValue(1024)
                .setMin(256).setMax(4096)
                .setTooltip(Component.translatable("vramtweak.gui.option.shadowMapMaxSize.tooltip"))
                .setRequirement(() -> cfg.vram.enabled && cfg.vram.shadowCapEnabled)
                .setSaveConsumer(v -> cfg.vram.shadowMapMaxSize = v)
                .build());

        // -- 格式降精度 --
        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.formatDownscale"),
                        cfg.vram.formatDownscale)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.formatDownscale.tooltip"))
                .setSaveConsumer(v -> cfg.vram.formatDownscale = v)
                .build());

        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.depthDownscale"),
                        cfg.vram.depthDownscale)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.depthDownscale.tooltip"))
                .setSaveConsumer(v -> cfg.vram.depthDownscale = v)
                .build());

        // -- 预算追踪 --
        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.budgetTracking"),
                        cfg.vram.budgetTracking)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.budgetTracking.tooltip"))
                .setSaveConsumer(v -> cfg.vram.budgetTracking = v)
                .build());

        vram.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.budgetWarningPercent"),
                        cfg.vram.budgetWarningPercent)
                .setDefaultValue(80)
                .setMin(50).setMax(95)
                .setTooltip(Component.translatable("vramtweak.gui.option.budgetWarningPercent.tooltip"))
                .setSaveConsumer(v -> cfg.vram.budgetWarningPercent = v)
                .build());

        // -- 调速器（内嵌） --
        vram.addEntry(eb.startTextDescription(
                Component.translatable("vramtweak.gui.section.governor")).build());

        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.governor.enabled"),
                        cfg.governor.enabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.governor.enabled.tooltip"))
                .setSaveConsumer(v -> cfg.governor.enabled = v)
                .build());

        vram.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.hysteresis"),
                        cfg.governor.hysteresis)
                .setDefaultValue(10)
                .setMin(2).setMax(30)
                .setTooltip(Component.translatable("vramtweak.gui.option.hysteresis.tooltip"))
                .setSaveConsumer(v -> cfg.governor.hysteresis = v)
                .build());

        vram.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.minDistance"),
                        cfg.governor.minDistance)
                .setDefaultValue(4)
                .setMin(2).setMax(16)
                .setTooltip(Component.translatable("vramtweak.gui.option.minDistance.tooltip"))
                .setSaveConsumer(v -> cfg.governor.minDistance = v)
                .build());

        vram.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.cooldownTicks"),
                        cfg.governor.cooldownTicks)
                .setDefaultValue(100)
                .setMin(20).setMax(600)
                .setTooltip(Component.translatable("vramtweak.gui.option.cooldownTicks.tooltip"))
                .setSaveConsumer(v -> cfg.governor.cooldownTicks = v)
                .build());

        // -- Voxy 显存控制（仅安装 Voxy 时显示）--
        if (test.vram.tweak.gpu.VoxyMemoryProbe.isAvailable()) {
            vram.addEntry(eb.startTextDescription(
                    Component.translatable("vramtweak.gui.section.voxy")).build());
            vram.addEntry(eb.startBooleanToggle(
                            Component.translatable("vramtweak.gui.option.voxy.enabled"),
                            cfg.voxy.enabled)
                    .setDefaultValue(false)
                    .setTooltip(Component.translatable("vramtweak.gui.option.voxy.enabled.tooltip"))
                    .setSaveConsumer(v -> cfg.voxy.enabled = v)
                    .build());
            vram.addEntry(eb.startIntField(
                            Component.translatable("vramtweak.gui.option.voxy.limitMB"),
                            cfg.voxy.geometryBufferLimitMB)
                    .setDefaultValue(1024)
                    .setMin(256).setMax(4096)
                    .setTooltip(Component.translatable("vramtweak.gui.option.voxy.limitMB.tooltip"))
                    .setSaveConsumer(v -> cfg.voxy.geometryBufferLimitMB = v)
                    .build());
        }

        // ================================================================
        // 2. 纹理优化
        // ================================================================
        var tex = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.texture"));

        tex.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.animationLimit"),
                        cfg.texture.animationLimit)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.animationLimit.tooltip"))
                .setSaveConsumer(v -> cfg.texture.animationLimit = v)
                .build());

        tex.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.maxAnimationFrames"),
                        cfg.texture.maxAnimationFrames)
                .setDefaultValue(32)
                .setMin(4).setMax(64)
                .setTooltip(Component.translatable("vramtweak.gui.option.maxAnimationFrames.tooltip"))
                .setSaveConsumer(v -> cfg.texture.maxAnimationFrames = v)
                .build());

        tex.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.atlasSizeLimit"),
                        cfg.texture.atlasSizeLimit)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.atlasSizeLimit.tooltip"))
                .setSaveConsumer(v -> cfg.texture.atlasSizeLimit = v)
                .build());

        tex.addEntry(eb.startIntField(
                        Component.translatable("vramtweak.gui.option.maxAtlasSize"),
                        cfg.texture.maxAtlasSize)
                .setDefaultValue(4096)
                .setMin(1024).setMax(16384)
                .setTooltip(Component.translatable("vramtweak.gui.option.maxAtlasSize.tooltip"))
                .setSaveConsumer(v -> cfg.texture.maxAtlasSize = v)
                .build());

        // ================================================================
        // 3. HUD 叠加层（按语义分组）
        // ================================================================
        var hud = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.hud"));

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.hud.enabled"),
                        cfg.hud.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.hud.enabled.tooltip"))
                .setSaveConsumer(v -> cfg.hud.enabled = v)
                .build());

        hud.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.offsetX"), cfg.hud.offsetX, 0, 200)
                .setDefaultValue(4)
                .setSaveConsumer(v -> cfg.hud.offsetX = v)
                .build());

        hud.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.offsetY"), cfg.hud.offsetY, 0, 200)
                .setDefaultValue(4)
                .setSaveConsumer(v -> cfg.hud.offsetY = v)
                .build());

        // -- 📈 性能 --
        hud.addEntry(eb.startTextDescription(
                Component.translatable("vramtweak.gui.section.performance")).build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps"), cfg.hud.showFps)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showFps = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFpsAvg"), cfg.hud.showFpsAvg)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showFpsAvg = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps1Percent"), cfg.hud.showFps1Percent)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.hud.showFps1Percent = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps01Percent"), cfg.hud.showFps01Percent)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.hud.showFps01Percent = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFrameTime"), cfg.hud.showFrameTime)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showFrameTime = v)
                .build());

        // -- 💾 显存 --
        hud.addEntry(eb.startTextDescription(
                Component.translatable("vramtweak.gui.section.memory")).build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showVram"), cfg.hud.showVram)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showVram = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showAtlas"), cfg.hud.showAtlas)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showAtlas = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showAllocations"), cfg.hud.showAllocations)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.hud.showAllocations = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showAllocBreakdown"),
                        cfg.hud.showAllocBreakdown)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.hud.showAllocBreakdown = v)
                .build());
        // Voxy option only appears when Voxy is installed — auto-hidden otherwise.
        if (test.vram.tweak.gpu.VoxyMemoryProbe.isAvailable()) {
            hud.addEntry(eb.startBooleanToggle(
                            Component.translatable("vramtweak.gui.option.showVoxy"),
                            cfg.hud.showVoxy)
                    .setDefaultValue(true)
                    .setTooltip(Component.translatable("vramtweak.gui.option.showVoxy.tooltip"))
                    .setSaveConsumer(v -> cfg.hud.showVoxy = v)
                    .build());
        }

        // -- ⚙️ 高级 --
        hud.addEntry(eb.startTextDescription(
                Component.translatable("vramtweak.gui.section.advanced")).build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showGovernor"), cfg.hud.showGovernor)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showGovernor = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showDownscales"), cfg.hud.showDownscales)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showDownscales = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showBudget"), cfg.hud.showBudget)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showBudget = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showGpu"), cfg.hud.showGpu)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showGpu = v)
                .build());
        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showGpuClocks"), cfg.hud.showGpuClocks)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showGpuClocks = v)
                .build());

        // ================================================================
        // 4. 诊断 + 信息
        // ================================================================
        var diag = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.diagnostic"));

        diag.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.verificationLog"),
                        cfg.diagnostic.verificationLog)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.verificationLog.tooltip"))
                .setSaveConsumer(v -> cfg.diagnostic.verificationLog = v)
                .build());

        diag.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.allocTracker"),
                        cfg.diagnostic.allocTracker)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.allocTracker.tooltip"))
                .setSaveConsumer(v -> {
                    cfg.diagnostic.allocTracker = v;
                    if (v) VramAllocationTracker.getInstance().activate();
                    else VramAllocationTracker.getInstance().deactivate();
                })
                .build());

        diag.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.allocSnapshotInterval"),
                        cfg.diagnostic.allocSnapshotInterval, 5, 120)
                .setDefaultValue(30)
                .setTooltip(Component.translatable("vramtweak.gui.option.allocSnapshotInterval.tooltip"))
                .setSaveConsumer(v -> cfg.diagnostic.allocSnapshotInterval = v)
                .build());

        // -- 系统信息 --
        diag.addEntry(eb.startTextDescription(
                Component.translatable("vramtweak.gui.section.info")).build());

        diag.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.gpu"),
                        GPUDetector.isReady()
                                ? GPUDetector.getGPU() + " — " + GPUDetector.getRenderer()
                                : Component.translatable("vramtweak.gui.option.gpu.pending").getString())
                .setDefaultValue("")
                .setTooltip(Component.translatable("vramtweak.gui.option.gpu.tooltip"))
                .build());

        diag.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.vramFree"),
                        GPUDetector.isReady()
                                ? VRAMOptimizer.queryTotalVRAM() + " MB"
                                : Component.translatable("vramtweak.gui.option.vramFree.pending").getString())
                .setDefaultValue("")
                .setTooltip(Component.translatable("vramtweak.gui.option.vramFree.tooltip"))
                .build());

        diag.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.fps"),
                        String.format("%.1f", MetricsEngine.getFps()))
                .setDefaultValue("0")
                .setTooltip(Component.translatable("vramtweak.gui.option.fps.tooltip"))
                .build());

        diag.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.textureAllocs"),
                        String.valueOf(MetricsEngine.getTextureAllocs()))
                .setDefaultValue("0")
                .build());

        diag.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.allocTracked"),
                        VramAllocationTracker.getInstance().isActive()
                                ? VramAllocationTracker.getInstance().getAliveCount() + " alive / "
                                  + VramAllocationTracker.getInstance().getTotalAllocs() + " total"
                                : Component.translatable("vramtweak.gui.option.allocTracker.disabled").getString())
                .setDefaultValue("disabled")
                .build());

        return builder.build();
    }
}



