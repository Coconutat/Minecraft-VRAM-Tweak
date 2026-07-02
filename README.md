# Minecraft VRAM Tweak

[**中文**](README_CN.md) | **English**

> Minecraft 26.2 Fabric VRAM optimization mod — Reduce GPU memory without touching shaders or resource packs.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

---
***
## Developer's Note

I originally wanted to make an optimization mod for my low-end AMD GPU because I noticed unstable frame rates. I couldn't do much about it, but with the help of AI I was able to bring my ideas to life.

However, as development progressed, I discovered that VRAM was actually the bottleneck. So I changed direction and created this mod optimized for low VRAM environments.

This mod is theoretically universal across GPU vendors.
***

## What It Does

VRAM Tweak intercepts GPU texture creation at the Blaze3D engine level via Mixin injection. It caps oversized texture atlases, downscales depth buffers, limits animation frames, and dynamically adjusts render distance when VRAM runs low — **all without modifying Sodium, Iris, or any third-party mod code**.

| Feature | How It Works | Triggered In Testing |
|---------|-------------|---------------------|
| **Atlas Size Cap** | `GpuDevice.createTexture()` width/height clamped to `maxAtlasSize` | ✅ 12× (16384→4096 px blocks.png) |
| **Depth Downscale** | D32_FLOAT → D16_UNORM shadow maps | ✅ 10× per session |
| **Format Downscale** | RGBA16F → RGBA8 color buffers (for heavy shader packs) | ⚠️ The current device test has not been triggered.Awaiting trigger, maybe one day we will need this. |
| **Shadow Map Cap** | Clamps shadow map resolution to `shadowMapMaxSize` | ⚠️ Vanilla ≤1024, already within limit |
| **Animation Limit** | Caps animated texture frame count | ✅ Stable |
| **Particle Limit** | Global particle count safety net | ✅ Experimental |
| **VRAM Governor** | Dynamically lowers render distance under VRAM pressure, restores when safe | ✅ Experimental |
| **Budget Tracking** | Per-frame VRAM polling + configurable warning threshold | ✅ Stable |

---

## HUD Overlay

Real-time performance overlay with **independent toggles** for each metric:

| Toggle | What It Shows |
|--------|--------------|
| FPS (Smooth) | 0.5s rolling-window frame rate |
| FPS (Average) | 5s sliding-window mean |
| 1% Low FPS | Slowest 1% of frames — perceived smoothness |
| 0.1% Low FPS | Worst 0.1% — stutter detection |
| Frame Time | Average milliseconds per frame |
| VRAM | Used / Total + percentage (color-coded) |
| Atlas Stats | Texture atlases tracked vs. size-capped |
| Allocations | GPU texture alloc/free counters |
| Downscales | Depth & format downscale trigger counts |
| Budget | Warning status + peak VRAM % |

Configure via Cloth Config GUI or `config/vram-tweak.json`.

---

## Commands

```
/vramtweak stats      — Print current VRAM + FPS stats to chat
/vramtweak dump       — Write a full diagnostic report to disk
/vramtweak hud        — Toggle HUD overlay on/off
/vramtweak benchmark  — Quick VRAM stress test
```

---

## Real-World Impact *(AMD R5 5600 + 32GB DDR4 3200 CL16 + AMD RX 6650 XT 8GB, MC 26.2 + Sodium + Iris + other mods)*

### Test Resource Pack & Shaders
- Resource Pack: [EXTREAL](https://www.bilibili.com/video/BV1CBoFB1Es1/) — Paid pack (trial version available)
- Shaders: [Spring v2](https://modrinth.com/shader/spring-shaders) — Publicly released by the author
***
### Before
| Metric | Value |
|--------|-------|
| VRAM Peak | 7820 / 8192 MB (95.4%) |
***
### After
| Metric | Value |
|--------|-------|
| VRAM Peak | 4728 / 8192 MB (57.7%) |
| Atlas Caps Triggered | 12 per session |
| Depth Downscales | 10 per session |
| Largest Atlas Reduction | 16384 → 4096 px (blocks.png) |
| Budget Warnings | 0 (never exceeded 80%) |

---

## Requirements

| Dependency | Type | Version |
|-----------|------|---------|
| **Sodium** | Hard | 0.9.0+ |
| Iris | Soft | 1.11+ *(shader compatibility)* |
| Cloth Config | Soft | 26.2+ *(GUI)* |

---

## GPU Support

| GPU Vendor | Auto-Detect | VRAM Tracking |
|-----------|------------|---------------|
| AMD | ✅ `GL_VENDOR` → auto-enable | ✅ `GL_ATI_meminfo` (KB-precise) |
| NVIDIA | Manual `vram.enabled=true` | ❌ No equivalent GL extension |
| Intel | Manual `vram.enabled=true` | ❌ No equivalent GL extension |

---

## Quick Start

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 26.2
2. Install [Sodium](https://modrinth.com/mod/sodium)
3. Drop `vram-tweak-1.0.0.jar` into `mods/`
4. Launch — AMD GPUs auto-enable. Others: set `vram.enabled: true` in config

---

## Configuration

All settings live in `config/vram-tweak.json`. Use Cloth Config GUI (Mod Menu → VRAM Tweak) for interactive setup.

```jsonc
{
  "vram": {
    "enabled": true,           // Master VRAM switch
    "shadowMapMaxSize": 1024,  // Shadow map resolution cap
    "formatDownscale": false,  // RGBA16F→RGBA8
    "depthDownscale": false,   // D32→D16
    "budgetTracking": false,   // VRAM usage monitor
    "budgetWarningPercent": 80 // Warn above this %
  },
  "texture": {
    "animationLimit": false,
    "maxAnimationFrames": 32,
    "atlasSizeLimit": false,   // Cap texture atlas size
    "maxAtlasSize": 4096
  },
  "governor": {
    "enabled": false,          // Dynamic render distance
    "hysteresis": 10,
    "minDistance": 4,
    "cooldownTicks": 100
  },
  "particle": {
    "enabled": false,
    "maxParticles": 2000
  },
  "hud": {
    "enabled": true,
    "showFps": true,
    "showFpsAvg": true,
    "showFps1Percent": false,
    "showFps01Percent": false,
    "showFrameTime": true,
    "showVram": true
    // ... more toggles
  }
}
```

---

## Building

```bash
git clone <repo-url>
cd Minecraft-AMD-GPU-Tweak
./gradlew build
# Output: build/libs/vram-tweak-1.0.0.jar
```

Requires JDK 25+ and Gradle 9.6+.

---

## Architecture

```
Mixin Injection Layer
├── MixinGpuDevice_VRAMOptimize   → createTexture() format/size interception
├── MixinGameRenderer_Metrics      → Per-frame stats + VRAM polling
├── MixinSpriteContents_Animation  → Animation frame capping
├── MixinParticleEngine_Cap        → Global particle limit
├── MixinOptions_RenderDistance    → VRAM Governor hook
├── MixinGui_Hud                   → HUD overlay rendering
└── MixinMinecraft_Hud             → HUD data collection

Core Modules (src/main)
├── VRAMOptimizer          → Format/size policy engine
├── VRAMGovernor           → Dynamic render distance controller
├── MetricsEngine          → Ring-buffer performance sampling
├── VramFrameCounter       → Sliding-window FPS + percentile lows
├── VerificationLogger     → Before/after audit trail
├── GPUDetector            → Vendor detection + VRAM query
└── VRAMConfig             → Gson-based config with 6 sections

Client Modules (src/client)
├── VramTweakHud           → Singleton overlay renderer
├── VramTweakCommand       → /vramtweak CLI
├── ClothConfigFactory     → GUI integration
└── ModMenuIntegration     → Mod Menu entry point
```

---

## License

Creative Commons Legal Code — See [LICENSE](LICENSE).
