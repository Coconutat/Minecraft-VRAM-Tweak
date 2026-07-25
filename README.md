# VRAM Tweak

**English** | [中文](README_CN.md)

> Minecraft 1.21.11 / 26.2 Fabric VRAM optimization mod — diagnose and reduce GPU VRAM usage without modifying shaders or resource packs.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11_|_26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---
  
<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>
  
***

## Overview

VRAM Tweak intercepts GPU texture creation at the OpenGL level via Mixin injection. It caps oversized texture atlases, limits animation frames, dynamically adjusts render distance under VRAM pressure, and tracks every GPU allocation with per-category breakdown — **without modifying Sodium, Iris, or any third-party mod**.

### Active Features

| Feature | How | Status |
|---------|-----|--------|
| **Atlas size cap** | Clamp texture atlas W/H ≤ `maxAtlasSize` | ✅ Stable |
| **Shadow map cap** | Limit shadow map resolution | ✅ Stable (Iris only) |
| **Animation frame limit** | Truncate animated texture frame count | ✅ Stable |
| **VRAM Governor** | Auto-lower render distance under VRAM pressure | ✅ Stable |
| **Budget tracking** | Per-frame VRAM polling + configurable alert | ✅ Stable |
| **AllocTracker** | Intercept every GPU alloc/free, categorize by type & source | ✅ Stable |

### Dormant Features (no trigger in 1.21.11)

| Feature | Reason |
|---------|--------|
| Format downscale (RGBA16F→RGBA8) | MC 1.21.11 `TextureFormat` only has RGBA8 |
| Depth downscale (D32→D16) | MC 1.21.11 only has DEPTH32 |

> **Note:** Toggling the mod OFF in GUI only affects **newly created** textures. Restart the game to reload at full resolution.

---

## AllocTracker — VRAM Forensics

Intercepts every `glTexImage2D` / `glDeleteTextures` call at the OpenGL level and classifies each allocation by category and source. Periodically writes aggregated snapshots to `logs/vram-tweak/mod.log`.

**Example output:**
```
[AllocTracker] ===== Snapshot t=0s =====
  GL Used: 4971/8192 MB (60%)
  Tracked: 4523 MB  |  Untracked: 448 MB
  ── By Category ──
    TEXTURE_ATLAS                  2772 MB  (61.3%)
    RENDER_TARGET_COLOR            1571 MB  (34.7%)
    UNKNOWN                         134 MB  ( 3.0%)
  ── By Source ──
    ATLAS_BLOCKS                   1536 MB  (34.0%)
    ATLAS_MISC                     1491 MB  (33.0%)
    ATLAS_ITEMS                     768 MB  (17.0%)
```

Enable via Config → Diagnostics → VRAM AllocTracker, or run `/vramtweak allocreport` in-game.

---

## HUD Overlay

Real-time performance overlay, each metric independently toggleable:

- FPS (smooth / avg / 1% Low / 0.1% Low), frame time
- VRAM (used / total + color-coded %)
- Atlas stats, texture alloc/free counts, downscale triggers
- Budget alert status, governor state, alloc breakdown

---

## Commands

```
/vramtweak stats       — Print VRAM + FPS stats to chat
/vramtweak dump        — Write ring-buffer CSV to disk
/vramtweak hud         — Toggle HUD overlay
/vramtweak benchmark   — VRAM stress test (ON vs OFF comparison)
/vramtweak allocreport — Print allocation breakdown by category & top-10 largest
```

---

## Performance

**Test environment:** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
MC 1.21.11 + Sodium 0.8.12 + Iris 1.10.7 + ScalableLux + 80+ mods

### VRAM Breakdown (AllocTracker, maxAtlasSize=2048)

| Source | Size | % |
|--------|------|---|
| Texture Atlases (blocks, items, misc) | ~2772 MB | 55% |
| Iris Render Targets (G-buffer) | ~1571 MB | 32% |
| Other (entities, GUI, font) | ~180 MB | 4% |
| Untracked (driver overhead, SSBO) | ~448 MB | 9% |
| **Total GL Reported** | **~4971 MB** | 60% of 8GB |

> The atlas cap reduces individual atlases from 16384px to the configured limit. Iris shader render targets (G-buffer) are the second-largest consumer and are not currently intercepted.

---

## Requirements

| Dependency | Type |
|-----------|------|
| **Sodium** | Hard |
| Iris | Soft (shader compat) |
| Cloth Config | Soft (GUI) |
| ModMenu | Soft (config button) |

**Platform:** Windows, Linux  
**Java:** 21+  
**GPU:** AMD (auto-detect), NVIDIA (manual enable), Intel (safe skip)

---

## Quick Start

1. Install [Fabric](https://fabricmc.net/use/)
2. Install [Sodium](https://modrinth.com/mod/sodium)
3. Install [Cloth Config API](https://modrinth.com/mod/cloth-config) (for GUI)
4. Drop `vram-tweak-*.jar` into `mods/`
5. Launch → Mod Menu → VRAM Tweak → Enable features

---

## Config

All settings in `config/vram-tweak.json`. Use Cloth Config GUI (Mod Menu → VRAM Tweak).

```jsonc
{
  "version": 1,
  "showExperimental": false,
  "vram": {
    "enabled": true,
    "shadowCapEnabled": true,
    "shadowMapMaxSize": 1024,
    "formatDownscale": false,     // dormant in 1.21.11
    "depthDownscale": false,      // dormant in 1.21.11
    "budgetTracking": false,
    "budgetWarningPercent": 80
  },
  "texture": {
    "animationLimit": false,
    "maxAnimationFrames": 32,
    "atlasSizeLimit": false,
    "maxAtlasSize": 4096
  },
  "diagnostic": {
    "enabled": true,
    "verificationLog": false,
    "allocTracker": false,
    "allocSnapshotInterval": 30,
    "logDirectory": "logs/vram-tweak"
  },
  "hud": {
    "enabled": true,
    "offsetX": 4, "offsetY": 4,
    "showFps": true, "showVram": true
  },
  "governor": {
    "enabled": false,
    "hysteresis": 10, "minDistance": 4, "cooldownTicks": 100
  },
  "experimental": {
    "pinnedMemory": false,
    "pinnedMemoryMinSize": 1024
  }
}
```

---

## Build

```bash
./gradlew build
# Output: build/libs/vram-tweak-*.jar
```

Requires JDK 21+.

---

## Architecture

```
Mixin Layer
├── MixinGpuDevice_VRAMOptimize      → createTexture() format/size cap
├── MixinGameRenderer_Metrics        → per-frame stats + VRAM poll + alloc snapshots
├── MixinGlStateManager_AllocTracker → glTexImage2D / glDeleteTextures interception
├── MixinGlFramebuffer_AllocTracker  → framebuffer attachment tracking
├── MixinSpriteContents_Animation    → animation frame truncation
├── MixinOptions_RenderDistance      → governor hook
├── MixinGui_Hud / MixinMinecraft_Hud → HUD overlay
└── MixinGlStateManager_PinnedMemory → AMD pinned memory (experimental)

Core (src/main)
├── allocation/              → AllocTracker: categories, sources, tracking, logging
├── VRAMOptimizer            → Format/size policy engine
├── VRAMGovernor             → Dynamic render distance controller
├── MetricsEngine            → Ring-buffer performance sampler
├── VramFrameCounter         → Sliding-window FPS + percentile lows
├── VerificationLogger       → Before/after audit trail
├── VramModLog               → Centralized file logger (logs/vram-tweak/mod.log)
├── GPUDetector              → GPU detection + VRAM queries
└── VRAMConfig               → Gson-based config

Client (src/client)
├── VramTweakHud             → Singleton overlay renderer
├── VramTweakCommand          → /vramtweak CLI (stats/dump/hud/benchmark/allocreport)
├── ClothConfigFactory       → GUI integration
└── ModMenuIntegration       → Mod Menu entry point
```

---

## License

CC0 1.0 Universal. See [LICENSE](LICENSE).
