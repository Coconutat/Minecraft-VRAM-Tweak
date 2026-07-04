# VRAM Tweak

[**中文**](README_CN.md) | **English**

> Minecraft 26.2 Fabric VRAM optimization mod — Reduce GPU memory without touching shaders or resource packs.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---
***
## Developer's Note

I originally wanted to make an optimization mod for my low-end AMD GPU because I noticed unstable frame rates. I couldn't do much about it, but with the help of AI I was able to bring my ideas to life.

However, as development progressed, I discovered that VRAM was actually the bottleneck. So I changed direction and created this mod optimized for low VRAM environments.

This mod is theoretically universal across GPU vendors.  

For users with GPUs that have 4GB or 6GB of video memory, this can reduce stuttering caused by insufficient video memory.  
***

## What It Does

VRAM Tweak intercepts GPU texture creation at the Blaze3D engine level via Mixin injection. It caps oversized texture atlases, downscales depth buffers, limits animation frames, and dynamically adjusts render distance when VRAM runs low — **all without modifying Sodium, Iris, or any third-party mod code**.

| Feature | How It Works | Triggered In Testing |
|---------|-------------|---------------------|
| **Atlas Size Cap** | `GpuDevice.createTexture()` W/H clamped to `maxAtlasSize` | ✅ 26 caps/session (blocks.png 16384→4096) |
| **Depth Downscale** | D32_FLOAT → D16_UNORM shadow maps | ✅ 10×/session, ~50% VRAM per shadow map |
| **Format Downscale** | RGBA16F → RGBA8 color buffers | ⚠️ Requires high-precision pack/shader |
| **Shadow Map Cap** | Clamp shadow map resolution ≤ `shadowMapMaxSize` | ⚠️ Vanilla ≤1024, within limit |
| **Animation Limit** | Cap animated texture frame count | ✅ Stable |
| **Particle Limit** | Global particle count safety net | ✅ Experimental |
| **VRAM Governor** | Auto-lower render distance under VRAM pressure | ✅ Experimental |
| **Budget Tracking** | Per-frame VRAM polling + configurable alert | ✅ Stable |

> ⚠️ **Important**: Toggling the mod ON/OFF via GUI takes effect immediately for *new* textures only. Textures already loaded into VRAM stay at their current size until you **restart the game**. If you disable the mod and VRAM usage doesn't increase, this is expected — restart to reload textures at full resolution.

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

## Real-World Impact

**Hardware:** AMD R5 5600 + 32GB DDR4 + RX 6650 XT 8GB  
**Software:** MC 26.2 + Sodium + Iris + resource pack + shaders

### Before vs After

| Metric | Before | After | Savings |
|--------|--------|-------|---------|
| VRAM Peak | 7820 / 8192 MB (95.4%) | **4728 / 8192 MB (57.7%)** | ~3 GB |
| Stability | Stuttering near VRAM limit | 0 budget warnings | Smooth & playable |

### Atlas Caps

**26 oversize caps** in one session:

| Atlas | Original | Capped | Savings |
|-------|---------|--------|---------|
| `blocks.png` | 16384×8192 | **4096×4096** | ~240 MB |
| `armor_trims.png` | 16384×8192 | **4096×4096** | ~240 MB |
| `items.png` | 8192×4096 | **4096×4096** | ~64 MB |

> **Total:** Atlas caps + depth downscales → ~**2.5 GB VRAM** saved. Usage dropped from 95.4% to 57.7%.

### Test Pack & Shaders
- Resource Pack: [EXTREAL](https://www.bilibili.com/video/BV1CBoFB1Es1/) (paid, trial available)
- Shaders: [Spring v2](https://modrinth.com/shader/spring-shaders) (public)

---

## Requirements

| Dependency | Type | Version |
|-----------|------|---------|
| **Sodium** | Hard | 0.9.0+ |
| Iris | Soft | 1.11+ *(shader compatibility)* |
| Cloth Config | Soft | 26.2+ *(GUI)* |
| ModMenu | Soft | 20.0+ *(config button)* |

**Platform:** Windows, Linux  
**Java:** 25+

> **Compatibility:** Tested with Iris + C2ME + Lithium + resource packs + shaders.

---

## GPU Support

| GPU Vendor | Auto-Detect | VRAM Tracking |
|-----------|------------|---------------|
| AMD | ✅ `GL_VENDOR` | ✅ `GL_ATI_meminfo` (KB-precise) |
| NVIDIA | ✅ `GL_VENDOR` | ✅ `GL_NVX_gpu_memory_info` (KB-precise) |
| Intel | ✅ `GL_VENDOR` | ❌ No dedicated VRAM (iGPU uses system RAM). Safe — automatically skipped. |

---

## Quick Start

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 26.2
2. Install [Sodium](https://modrinth.com/mod/sodium)
3. Drop `vram-tweak-x.x.x.jar` into `mods/`
4. Launch — enable via Mod Menu → VRAM Tweak config

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
  },
  "cas": {                          // 🆕 FSR CAS sharpening
    "enabled": false,
    "sharpness": 0.8
  }
}
```

---

## Building

```bash
git clone <repo-url>
cd Minecraft-AMD-GPU-Tweak
./gradlew build
# Output: build/libs/vram-tweak-x.x.x.jar
```

Requires JDK 25+ and Gradle 9.6+.

---

## Architecture

```
Mixin Injection Layer
├── MixinGpuDevice_VRAMOptimize    → createTexture() format/size cap
├── MixinGameRenderer_Metrics      → Per-frame stats + VRAM polling
├── MixinGameRenderer_CAS          → FSR CAS sharpening pass
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
└── VRAMConfig             → Gson-based config with 7 sections

Client Modules (src/client)
├── CasShader              → GLSL CAS fullscreen post-process pass
├── VramTweakHud           → Singleton overlay renderer
├── VramTweakCommand       → /vramtweak CLI
├── ClothConfigFactory     → GUI integration
└── ModMenuIntegration     → Mod Menu entry point
```

---

## License

Creative Commons Legal Code — See [LICENSE](LICENSE).
