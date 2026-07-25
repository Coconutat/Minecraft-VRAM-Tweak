# VRAM Tweak

**English** | [中文](README_CN.md)

> Minecraft 26.2 Fabric VRAM optimization mod — diagnose and reduce GPU VRAM usage.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---

<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>

***

## Overview

VRAM Tweak intercepts GPU texture creation at the OpenGL level via Mixin injection. It caps oversized texture atlases, downscales depth buffers, limits animation frames, and dynamically adjusts render distance — **without modifying Sodium, Iris, or any third-party mod**. Also includes a built-in VRAM forensics tool (AllocTracker) that intercepts every GPU allocation and classifies it by category and source.

### Active Features

| Feature | How | Status |
|---------|-----|--------|
| **Atlas size cap** | Clamp texture atlas W/H ≤ `maxAtlasSize` | ✅ Stable |
| **Depth downscale** | D32_FLOAT → D16_UNORM | ✅ Verified |
| **Shadow map cap** | Limit shadow map resolution | ✅ Stable |
| **Animation frame limit** | Truncate animated texture frame count | ✅ Stable |
| **VRAM Governor** | Auto-lower render distance under VRAM pressure | ✅ Stable |
| **Budget tracking** | Per-frame VRAM polling + configurable alert | ✅ Stable |
| **AllocTracker** | Intercept every GPU alloc/free, categorize by type & source | ✅ Stable |

### Conditional Features

| Feature | Trigger |
|---------|---------|
| Format downscale (RGBA16F→RGBA8) | Requires high-precision resource packs or shader packs |

> **Note:** Toggling the mod OFF in GUI only affects **newly created** textures. Restart the game to reload at full resolution.

---

## AllocTracker — VRAM Forensics

Intercepts every `glTexImage2D` / `glDeleteTextures` call and classifies each allocation by category and source. Periodically writes aggregated snapshots to `logs/vram-tweak/mod.log`.

**Example output:**
```
[AllocTracker] ===== Snapshot t=0s =====
  GL Used: 4971/8192 MB (60%)
  ── By Category ──
    TEXTURE_ATLAS                  2772 MB  (61%)
    RENDER_TARGET_COLOR            1571 MB  (35%)
  ── By Source ──
    ATLAS_BLOCKS                   1536 MB  (34%)
    ATLAS_ITEMS                     768 MB  (17%)
```

Enable via Config → Diagnostics → VRAM AllocTracker, or `/vramtweak allocreport`.

---

## HUD Overlay

Real-time overlay, each metric independently toggleable: FPS (smooth/avg/1%/0.1%), frame time, VRAM, atlas stats, alloc/free counts, governor status, alloc breakdown.

---

## Commands

```
/vramtweak stats       — VRAM + FPS stats
/vramtweak dump        — Ring-buffer CSV to disk
/vramtweak hud         — Toggle HUD
/vramtweak benchmark   — VRAM stress test (ON vs OFF)
/vramtweak allocreport — Allocation breakdown by category + top-10 largest
```

---

## Performance

**HW:** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
**SW:** MC 26.2 + Sodium 0.9.0 + Iris 1.11 + ScalableLux + 80+ mods

### VRAM Breakdown (AllocTracker, maxAtlasSize=2048)

| Source | Size | % |
|--------|------|---|
| Texture Atlases (blocks, items, misc) | ~2772 MB | 55% |
| Iris Render Targets (G-buffer) | ~1571 MB | 32% |
| Other (entities, GUI, font) | ~180 MB | 4% |
| Untracked (driver, SSBO) | ~448 MB | 9% |
| **Total** | **~4971 MB** | 60% of 8GB |

> The atlas cap reduces individual atlases from 16384px to the configured limit. Iris G-buffers are the second-largest consumer and are not currently intercepted.

---

## Requirements

| Dependency | Type |
|-----------|------|
| **Sodium** | Suggested |
| Iris | Soft (shader compat) |
| Cloth Config | Soft (GUI) |
| ModMenu | Soft (config button) |

**Platform:** Windows, Linux  
**Java:** 25+  
**GPU:** AMD (auto-detect), NVIDIA (manual enable), Intel (safe skip)

---

## Quick Start

1. Install [Fabric](https://fabricmc.net/use/) for Minecraft 26.2
2. Install [Sodium](https://modrinth.com/mod/sodium) 0.9.0
3. Install [Cloth Config API](https://modrinth.com/mod/cloth-config)
4. Drop `vram-tweak-*.jar` into `mods/`
5. Launch → Mod Menu → VRAM Tweak → Enable

---

## Config

```jsonc
{
  "version": 1,
  "showExperimental": false,
  "vram": {
    "enabled": true,
    "shadowCapEnabled": true, "shadowMapMaxSize": 1024,
    "formatDownscale": false, "depthDownscale": false,
    "budgetTracking": false, "budgetWarningPercent": 80
  },
  "texture": {
    "animationLimit": false, "maxAnimationFrames": 32,
    "atlasSizeLimit": false, "maxAtlasSize": 4096
  },
  "diagnostic": {
    "enabled": true, "verificationLog": false,
    "allocTracker": false, "allocSnapshotInterval": 30,
    "logDirectory": "logs/vram-tweak"
  },
  "hud": { "enabled": true, "offsetX": 4, "offsetY": 4 },
  "governor": { "enabled": false, "hysteresis": 10, "minDistance": 4, "cooldownTicks": 100 },
  "experimental": { "pinnedMemory": false, "pinnedMemoryMinSize": 1024 }
}
```

---

## Build

```bash
./gradlew build
# Output: build/libs/vram-tweak-*.jar
```

Requires JDK 25+.

---

## Architecture

```
Mixin Layer
├── MixinGpuDevice_VRAMOptimize      → createTexture() format/size cap
├── MixinGameRenderer_Metrics        → per-frame stats + alloc snapshots
├── MixinGlStateManager_AllocTracker → glTexImage2D / glDeleteTextures
├── MixinGlFramebuffer_AllocTracker  → framebuffer attachment tracking
├── MixinSpriteContents_Animation    → animation frame truncation
├── MixinOptions_RenderDistance      → governor hook
├── MixinGui_Hud / MixinMinecraft_Hud → HUD overlay
├── MixinGlStateManager_PinnedMemory → AMD pinned memory (experimental)
└── MixinGameRenderer_PerfMonitor    → AMD GPU clocks

Core (src/main)
├── allocation/              → AllocTracker: categories, tracking, logging
├── VRAMOptimizer / VRAMGovernor / MetricsEngine / VramFrameCounter
├── VerificationLogger / VramModLog / GPUDetector / VRAMConfig

Client (src/client)
├── VramTweakHud / VramTweakCommand / ClothConfigFactory / ModMenuIntegration
└── AMDPerfMonitor / PinnedMemory + PBO pool
```

---

## License

CC0 1.0 Universal. See [LICENSE](LICENSE).
