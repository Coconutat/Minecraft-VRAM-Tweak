# VRAM Tweak

**English** | [中文](README_CN.md)

> Minecraft 1.21.11 / 26.2 Fabric VRAM optimization mod — reduce GPU VRAM usage without modifying shaders or resource packs.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11_|_26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---
<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>
***
## Developer's Note
I initially set out to create an optimization mod for my low-end AMD GPU because I noticed unstable frame rates. While I couldn't fix the hardware limitations directly, modern AI tools allowed me to bring my modding ideas to life.
However, as development progressed, I discovered that the real issue was a VRAM bottleneck. I therefore shifted my focus to creating a mod optimized for low VRAM usage.
In theory, this mod is universally compatible.
For users with GPUs featuring 4GB or 6GB of VRAM, it helps reduce stuttering caused by insufficient video memory.
***

## Overview

VRAM Tweak intercepts GPU texture creation at the Blaze3D engine level via Mixin injection. It caps oversized texture atlases, limits animation frames, and dynamically adjusts render distance under VRAM pressure — **without modifying Sodium, Iris, or any third-party mod**.

| Feature | How | Status |
|---------|-----|--------|
| **Atlas size cap** | Clamp `GlDevice.createTexture()` W/H ≤ `maxAtlasSize` | ✅ 26 caps/session |
| **Format downscale** | High-precision → RGBA8 | ⚠️ Dormant (1.21.11 only has RGBA8) |
| **Depth downscale** | High-precision depth → lower | ⚠️ Dormant (1.21.11 only has DEPTH32) |
| **Shadow map cap** | Clamp square depth texture resolution | ✅ Stable |
| **FSR CAS sharpening** | Post-process fullscreen shader on main framebuffer | 🆕 Experimental |
| **Animation frame limit** | Truncate animated texture frame count | ✅ Stable |
| **Particle count cap** | Global particle count safety net | ✅ Experimental |
| **VRAM Governor** | Auto-lower render distance under pressure | ✅ Experimental |
| **Budget tracking** | Per-frame VRAM polling + configurable alert | ✅ Stable |

> **Note:** Toggling the mod OFF in GUI only affects **newly created** textures. Existing VRAM textures stay at capped size — **restart the game** to reload at full resolution.

---

## HUD Overlay

Real-time performance overlay, each metric independently toggleable: FPS (smooth/avg/1%/0.1%), frame time, VRAM usage %, atlas stats, texture alloc/free counts, budget status.

---

## Commands

```
/vramtweak stats      — Print VRAM + FPS stats to chat
/vramtweak dump       — Write ring-buffer CSV to disk
/vramtweak hud        — Toggle HUD overlay
/vramtweak benchmark  — Quick VRAM stress test
```

---

## Test Results

**HW:** AMD R5 5600 + 32GB DDR4 + RX 6650 XT 8GB  
**SW:** MC 1.21.11 + Sodium 0.8.13 + Iris 1.10.7 (1.21.11) / MC 26.2 + Sodium 0.9.0 + Iris 1.11.1 (26.2)

### Atlas Caps

44 texture atlases tracked, **26 oversize caps** in one session:

| Atlas | Original | Capped | Savings |
|-------|---------|--------|---------|
| `blocks.png` | 16384×8192 | **4096×4096** | ~240 MB |
| `blocks_n.png` / `blocks_s.png` | 16384×8192 | **4096×4096** | ~240 MB |
| `armor_trims.png` | 16384×8192 | **4096×4096** | ~240 MB |
| `items.png` | 8192×4096 | **4096×4096** | ~64 MB |
| `shield_patterns.png` | 8192×4096 | **4096×4096** | ~64 MB |
| `banner_patterns.png` | 8192×4096 | **4096×4096** | ~64 MB |

> **Total:** 6 atlases from 16384px → 4096px, saving ~**900 MB VRAM**.

### Session Stats

| Metric | Value |
|--------|-------|
| Atlas tracked | 44 |
| Atlas caps | **26** |
| Format downscales | 0 (no 16-bit format in 1.21.11) |
| Depth downscales | 0 (only DEPTH32 in 1.21.11) |
| Shadow caps | 0 |
| Budget warns | 0 |
| Crashes | **0** (90+ mod compatible) |

---

## Requirements

| Dependency | Type | Version |
|-----------|------|---------|
| **Sodium** | Hard | 0.8.13+ (1.21.11) / 0.9.0+ (26.2) |
| Iris | Soft | 1.10+ / 1.11+ *(shader compat)* |
| Cloth Config | Soft | 21.11+ / 26.2+ *(GUI)* |
| ModMenu | Soft | 17.0+ / 20.0+ *(config button)* |

**Platform:** Windows, Linux  
**Java:** 21+ (1.21.11) / 25+ (26.2)

> **Compatibility:** Tested with 90+ mods including C2ME, Lithium, Iris, Continuity, Entity Culling.

---

## GPU Support

| GPU | Auto-Detect | VRAM Tracking |
|-----|------------|---------------|
| AMD | ✅ `GL_VENDOR` | ✅ `GL_ATI_meminfo` |
| NVIDIA | ✅ `GL_VENDOR` | ✅ `GL_NVX_gpu_memory_info` |
| Intel | ✅ `GL_VENDOR` | Safe skip (iGPU, no discrete VRAM) |

---

## Quick Start

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 26.2
2. Install [Sodium](https://modrinth.com/mod/sodium) ```For 26.2 , If you install Iris, Sodium must be version 0.9.0, because Iris is not compatible with versions higher than 0.9.0.  For 1.21.11 , Sodium must be version 0.8.13-beta-1, because Iris is not compatible with versions higher than 0.8.13-beta-1.If you don't use Iris, there may be no restrictions.```
3. Install [Cloth Config API](https://modrinth.com/mod/cloth-config)
4. Install [Iris](https://modrinth.com/mod/iris) `this is optional`
5. Drop `vram-tweak-x.x.x.jar` into `mods/`
6. Launch — Open Mod Menu → VRAM Tweak → Enable features

---

## Config

All settings in `config/vram-tweak.json`. Use Cloth Config GUI for interactive config.

```jsonc
{
  "vram": {
    "enabled": true,
    "shadowMapMaxSize": 1024,
    "formatDownscale": false,  // dormant in 1.21.11 (only RGBA8); 26.2 may trigger
    "depthDownscale": false,   // dormant in 1.21.11 (only DEPTH32); 26.2 triggers
    "budgetTracking": false,
    "budgetWarningPercent": 80
  },
  "texture": {
    "animationLimit": false,
    "maxAnimationFrames": 32,
    "atlasSizeLimit": false,
    "maxAtlasSize": 4096
  },
  "governor": {
    "enabled": false,
    "hysteresis": 10,
    "minDistance": 4,
    "cooldownTicks": 100
  },
  "particle": {
    "enabled": false,
    "maxParticles": 2000
  },
  "cas": {                          // 🆕 FSR CAS sharpening
    "enabled": false,
    "sharpness": 0.8
  },
  "hud": {
    "enabled": true,
    "showFps": true
    // ... more toggles
  }
}
```

---

## Build

```bash
git clone <repo-url>
cd Minecraft-VRAM-Tweak
./gradlew build
# Output: build/libs/vram-tweak-x.x.x.jar
```

Requires JDK 21+ and Gradle 9.6+.

---

## Architecture

```
Mixin Layer
├── MixinGpuDevice_VRAMOptimize   → createTexture() format/size cap
├── MixinGameRenderer_Metrics    → per-frame stats + VRAM poll
├── MixinGameRenderer_CAS        → FSR CAS sharpening pass
├── MixinSpriteContents_Animation → animation frame truncation
├── MixinParticleEngine_Cap      → global particle limit
├── MixinOptions_RenderDistance  → governor hook
├── MixinGui_Hud                 → HUD overlay render
└── MixinMinecraft_Hud           → HUD data collection

Core (src/main)
├── VRAMOptimizer          → Format/size policy engine
├── VRAMGovernor           → Dynamic render distance controller
├── MetricsEngine          → Ring-buffer performance sampler
├── VramFrameCounter       → Sliding-window FPS + percentile lows
├── VerificationLogger     → Audit trail
├── GPUDetector            → Vendor detection + VRAM queries
└── VRAMConfig             → Gson-based 8-section config

Client (src/client)
├── CasShader              → GLSL CAS fullscreen pass
├── VramTweakHud           → Singleton overlay renderer
├── VramTweakCommand       → /vramtweak CLI
├── ClothConfigFactory     → GUI integration
└── ModMenuIntegration     → Mod Menu entry point
```

## License

CC0 1.0 Universal. See [LICENSE](LICENSE).
