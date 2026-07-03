# VRAM Tweak

**English** | [中文](README_CN.md)

> Minecraft 1.21.11 Fabric VRAM optimization mod — reduce GPU VRAM usage without modifying shaders or resource packs.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---

## Overview

VRAM Tweak intercepts GPU texture creation at the Blaze3D engine level via Mixin injection. It caps oversized texture atlases, limits animation frames, and dynamically adjusts render distance under VRAM pressure — **without modifying Sodium, Iris, or any third-party mod**.

| Feature | How | Status |
|---------|-----|--------|
| **Atlas size cap** | Clamp `GlDevice.createTexture()` W/H ≤ `maxAtlasSize` | ✅ 26 caps/session |
| **Format downscale** | High-precision → RGBA8 | ⚠️ Dormant (1.21.11 only has RGBA8) |
| **Depth downscale** | High-precision depth → lower | ⚠️ Dormant (1.21.11 only has DEPTH32) |
| **Shadow map cap** | Clamp square depth texture resolution | ✅ Stable |
| **S3TC compression** | BC1/BC3 DXT at `GlCommandEncoder.writeToTexture()` | 🆕 Experimental |
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
**SW:** MC 1.21.11 + Sodium 0.8.13 + Iris 1.10.7 + 90+ mods

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
| **Sodium** | Hard | 0.8.13+ *(1.21.11)* |
| Iris | Soft | 1.10+ *(shader compat)* |
| Cloth Config | Soft | 21.11+ *(GUI)* |
| ModMenu | Soft | 17.0+ *(config button)* |

**Platform:** Windows, Linux  
**Java:** 21+

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

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 1.21.11
2. Install [Sodium](https://modrinth.com/mod/sodium)
3. Drop `vram-tweak-x.x.x.jar` into `mods/`
4. Launch — enable in Mod Menu → VRAM Tweak

---

## Config

All settings in `config/vram-tweak.json`. Use Cloth Config GUI for interactive config.

```jsonc
{
  "vram": {
    "enabled": true,
    "shadowMapMaxSize": 1024,
    "formatDownscale": false,  // dormant in 1.21.11 (TextureFormat only has RGBA8)
    "depthDownscale": false,   // dormant (TextureFormat only has DEPTH32)
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
  "s3tc": {                         // 🆕 S3TC texture compression (experimental)
    "enabled": false,
    "compressBlockAtlas": true,
    "compressEntityTextures": false,
    "compressGuiTextures": false,
    "compressOther": false
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
├── MixinGpuDevice_VRAMOptimize   → createTexture() format/size/S3TC-flag
├── MixinGlCommandEncoder_S3TC   → writeToTexture() DXT compression
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
├── S3TCDxtEncoder         → Pure Java BC1/BC3 compressor
├── TextureCategory        → Label/format/size classifier
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
