package test.vram.tweak.gui;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.MetricsEngine;
import test.vram.tweak.gpu.GPUDetector;
import test.vram.tweak.vram.VRAMGovernor;
import test.vram.tweak.vram.VRAMOptimizer;

/**
 * Cloth Config GUI — VRAM / Texture / Governor / Particle / HUD.
 */
public class ClothConfigFactory {

    public static Screen create(Screen parent) {
        var builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("vramtweak.gui.title"))
                .setSavingRunnable(() -> {
                    VRAMConfig.save();
                    VRAMOptimizer.reload();
                    VRAMGovernor.reload();
                });

        var eb = builder.entryBuilder();
        var cfg = VRAMConfig.getInstance();

        // ---- VRAM ----
        var vram = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.vram"));

        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.vram.enabled"),
                        cfg.vram.enabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.vram.enabled.tooltip"))
                .setSaveConsumer(v -> { cfg.vram.enabled = v; VRAMOptimizer.reload(); })
                .build());

        vram.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.shadowMapMaxSize"),
                        cfg.vram.shadowMapMaxSize, 256, 4096)
                .setDefaultValue(1024)
                .setTooltip(Component.translatable("vramtweak.gui.option.shadowMapMaxSize.tooltip"))
                .setSaveConsumer(v -> cfg.vram.shadowMapMaxSize = v)
                .build());

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

        vram.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.budgetTracking"),
                        cfg.vram.budgetTracking)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.budgetTracking.tooltip"))
                .setSaveConsumer(v -> cfg.vram.budgetTracking = v)
                .build());

        vram.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.budgetWarningPercent"),
                        cfg.vram.budgetWarningPercent, 50, 95)
                .setDefaultValue(80)
                .setTooltip(Component.translatable("vramtweak.gui.option.budgetWarningPercent.tooltip"))
                .setSaveConsumer(v -> cfg.vram.budgetWarningPercent = v)
                .build());

        // ---- Texture ----
        var tex = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.texture"));

        tex.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.animationLimit"),
                        cfg.texture.animationLimit)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.animationLimit.tooltip"))
                .setSaveConsumer(v -> cfg.texture.animationLimit = v)
                .build());

        tex.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.maxAnimationFrames"),
                        cfg.texture.maxAnimationFrames, 4, 64)
                .setDefaultValue(32)
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

        tex.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.maxAtlasSize"),
                        cfg.texture.maxAtlasSize, 1024, 16384)
                .setDefaultValue(4096)
                .setTooltip(Component.translatable("vramtweak.gui.option.maxAtlasSize.tooltip"))
                .setSaveConsumer(v -> cfg.texture.maxAtlasSize = v)
                .build());

        // ---- Governor ----
        var governor = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.governor"));

        governor.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.governor.enabled"),
                        cfg.governor.enabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.governor.enabled.tooltip"))
                .setSaveConsumer(v -> cfg.governor.enabled = v)
                .build());

        governor.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.hysteresis"),
                        cfg.governor.hysteresis, 2, 30)
                .setDefaultValue(10)
                .setTooltip(Component.translatable("vramtweak.gui.option.hysteresis.tooltip"))
                .setSaveConsumer(v -> cfg.governor.hysteresis = v)
                .build());

        governor.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.minDistance"),
                        cfg.governor.minDistance, 2, 16)
                .setDefaultValue(4)
                .setTooltip(Component.translatable("vramtweak.gui.option.minDistance.tooltip"))
                .setSaveConsumer(v -> cfg.governor.minDistance = v)
                .build());

        governor.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.cooldownTicks"),
                        cfg.governor.cooldownTicks, 20, 600)
                .setDefaultValue(100)
                .setTooltip(Component.translatable("vramtweak.gui.option.cooldownTicks.tooltip"))
                .setSaveConsumer(v -> cfg.governor.cooldownTicks = v)
                .build());

        // ---- Particle ----
        var particle = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.particle"));

        particle.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.particle.enabled"),
                        cfg.particle.enabled)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.particle.enabled.tooltip"))
                .setSaveConsumer(v -> cfg.particle.enabled = v)
                .build());

        particle.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.maxParticles"),
                        cfg.particle.maxParticles, 200, 10000)
                .setDefaultValue(2000)
                .setTooltip(Component.translatable("vramtweak.gui.option.maxParticles.tooltip"))
                .setSaveConsumer(v -> cfg.particle.maxParticles = v)
                .build());

        // ---- HUD ----
        var hud = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.hud"));

        // ---- Diagnostic ----
        var diag = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.diagnostic"));

        diag.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.verificationLog"),
                        cfg.diagnostic.verificationLog)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.verificationLog.tooltip"))
                .setSaveConsumer(v -> cfg.diagnostic.verificationLog = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.hud.enabled"),
                        cfg.hud.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.hud.enabled.tooltip"))
                .setSaveConsumer(v -> cfg.hud.enabled = v)
                .build());

        hud.addEntry(eb.startSelector(
                        Component.translatable("vramtweak.gui.option.anchor"),
                        new String[]{"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"},
                        cfg.hud.anchor)
                .setDefaultValue("TOP_LEFT")
                .setTooltip(Component.translatable("vramtweak.gui.option.anchor.tooltip"))
                .setSaveConsumer(v -> cfg.hud.anchor = v)
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

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps"), cfg.hud.showFps)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.showFps.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showFps = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFpsAvg"), cfg.hud.showFpsAvg)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.showFpsAvg.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showFpsAvg = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps1Percent"), cfg.hud.showFps1Percent)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.showFps1Percent.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showFps1Percent = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFps01Percent"), cfg.hud.showFps01Percent)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("vramtweak.gui.option.showFps01Percent.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showFps01Percent = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showFrameTime"), cfg.hud.showFrameTime)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showFrameTime = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showVram"), cfg.hud.showVram)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.hud.showVram = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showAllocations"), cfg.hud.showAllocations)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.hud.showAllocations = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showAtlas"), cfg.hud.showAtlas)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.showAtlas.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showAtlas = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showDownscales"), cfg.hud.showDownscales)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.showDownscales.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showDownscales = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(
                        Component.translatable("vramtweak.gui.option.showBudget"), cfg.hud.showBudget)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("vramtweak.gui.option.showBudget.tooltip"))
                .setSaveConsumer(v -> cfg.hud.showBudget = v)
                .build());

        hud.addEntry(eb.startIntSlider(
                        Component.translatable("vramtweak.gui.option.bgAlpha"),
                        (int)(cfg.hud.bgAlpha * 100), 0, 80)
                .setDefaultValue(35)
                .setTooltip(Component.translatable("vramtweak.gui.option.bgAlpha.tooltip"))
                .setSaveConsumer(v -> cfg.hud.bgAlpha = v / 100f)
                .build());

        // ---- Info (read-only) ----
        var info = builder.getOrCreateCategory(Component.translatable("vramtweak.gui.category.info"));

        info.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.gpu"),
                        GPUDetector.isReady()
                                ? GPUDetector.getGPU() + " — " + GPUDetector.getRenderer()
                                : Component.translatable("vramtweak.gui.option.gpu.pending").getString())
                .setDefaultValue("")
                .setTooltip(Component.translatable("vramtweak.gui.option.gpu.tooltip"))
                .build());

        info.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.vramFree"),
                        GPUDetector.isReady()
                                ? VRAMOptimizer.queryTotalVRAM() + " MB"
                                : Component.translatable("vramtweak.gui.option.vramFree.pending").getString())
                .setDefaultValue("")
                .setTooltip(Component.translatable("vramtweak.gui.option.vramFree.tooltip"))
                .build());

        info.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.fps"),
                        String.format("%.1f", MetricsEngine.getFps()))
                .setDefaultValue("0")
                .setTooltip(Component.translatable("vramtweak.gui.option.fps.tooltip"))
                .build());

        info.addEntry(eb.startStrField(
                        Component.translatable("vramtweak.gui.option.textureAllocs"),
                        String.valueOf(MetricsEngine.getTextureAllocs()))
                .setDefaultValue("0")
                .setTooltip(Component.translatable("vramtweak.gui.option.textureAllocs.tooltip"))
                .build());

        return builder.build();
    }
}



