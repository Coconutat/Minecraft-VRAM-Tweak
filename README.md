# VRAM Tweak

**English** | [中文](README_CN.md)

> Minecraft 26.2 Fabric forensics-driven VRAM optimization mod — diagnose where VRAM goes, then reduce what is proven safe to reduce.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---

<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>

***

## Overview

VRAM Tweak intercepts GPU texture creation at the Blaze3D abstraction layer via Mixin injection. It caps oversized texture atlases, downscales depth buffers, dynamically adjusts render distance under VRAM pressure, and clamps unsafe Voxy geometry limits — **without modifying Sodium, Iris, or any third-party mod code**. Its core is the built-in VRAM forensics tool (AllocTracker), which classifies every tracked GPU allocation by category and source.

### Active Features

| Feature | How | Status |
|---------|-----|--------|
| **AllocTracker** | Track GPU alloc/free, categorize by type & source | ✅ Core |
| **Depth downscale** | D32_FLOAT → D16_UNORM | ✅ Verified |
| **Atlas size cap** | Clamp texture atlas W/H ≤ `maxAtlasSize` | ⚠️ Verified, some shaders may shade a block face dark |
| **VRAM Governor** | Auto-lower render distance under VRAM pressure, active enforcement | ✅ Stable |
| **Budget tracking** | Periodic VRAM polling + configurable alert | ✅ Stable |
| **Voxy geometry clamp** | Prevent unsafe low Voxy geometry limits (min 1024MB) | ✅ Verified (1024 safe; 512 thrash; 2048 worse on 8GB) |

> ⚠️ **The render distance reduction is temporary and dynamic.** Your settings menu still shows the original value you configured. To see the actual effective render distance, check the HUD overlay — it displays the governor's current cap in real time. When VRAM recovers, the cap lifts automatically.

### Conditional Features

| Feature | Trigger |
|---------|---------|
| Format downscale (RGBA16F→RGBA8) | Requires high-precision resource packs or shader packs |

> **Note:** Toggling the mod OFF in GUI only affects **newly created** textures. Restart the game to reload at full resolution.

---

## AllocTracker — VRAM Forensics

Intercepts GPU resource creation/release at the Blaze3D abstraction layer (`GpuDevice.createTexture`, `GlTexture.destroyImmediately`, `GlBuffer`/`BufferStorage` lifecycle) and classifies each allocation by category and source. Periodically writes aggregated snapshots to `logs/vram-tweak/mod.log`.

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
/vramtweak allocreport — Allocation breakdown by category + top-10 largest + GL−tracked trend
/vramtweak phase <name> — Mark a test phase in mod.log + verify log
/vramtweak probe       — Dump live VRAM probe details (ATI pools, Voxy)
/vramtweak governor on|off|status — Toggle/query the VRAM governor
```

---

## Performance

**HW:** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
**SW:** MC 26.2 + Sodium 0.9.1 + Iris 1.11.2 + ComplementaryReimagined_r5.8.1 + EuphoriaPatches + Voxy 0.2.18-beta + 80+ mods

### Real-world VRAM forensics (2026-08-15, AllocTracker)

| Voxy geometry limit | Peak (VBO-based) | Tracked | Untracked | Note |
|---|---|---|---|---|
| 512MB (old session) | 8028/8192 MB (97%) | — | — | Voxy node hierarchy thrash |
| 1024MB, atlas 8192 | 7576/8192 MB (92%) | 3118 MB | 4458 MB | No thrash; pressure = Voxy + 3×8192² atlases + shader pack |
| 2048MB, atlas 8192 | 8028/8192 MB (97%) | 2993 MB | 5035 MB | Extra 1GB geometry pushes the card to the edge |
| **1024MB, atlas 4096** | **6723/8192 MB (82%)** | **1895 MB** | **4828 MB** | **Recommended P2 baseline** |

At 1024MB with atlas 8192 the tracked side is dominated by **three 8192² texture atlases** (`blocks`, `blocks_n`, `blocks_s`, each ~341MB with mips, ~1023MB total) plus Sodium buffer geometry (~750MB). With **atlas 4096** the atlas total drops to ~944MB. The untracked side is mostly **Voxy raw GL** (geometry buffer ~1GB + model atlas ~0.5GB) and **Iris shader raw GL** (~2.5–2.9GB).

> **Recommendation:** keep the Voxy geometry limit at 1024MB and use `maxAtlasSize=4096`. 512MB causes thrash; 2048MB makes an 8GB card worse; 1024+4096 is the current best measured combination.

### VRAM probe reliability

On the tested AMD Windows driver, `GL_ATI_meminfo` reports the **same value for all three pool tokens at startup**, and the texture/renderbuffer tokens can return garbage at runtime. **Only `GL_VBO_FREE_MEMORY_ATI` (0x87FB) is used for calculations**; the other two are logged as forensic information. Do not sum the three pools on this driver.

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
  "vram": {
    "enabled": true,
    "formatDownscale": false, "depthDownscale": false,
    "budgetTracking": false, "budgetWarningPercent": 80
  },
  "texture": {
    "atlasSizeLimit": false, "maxAtlasSize": 4096
  },
  "diagnostic": {
    "enabled": true, "verificationLog": false,
    "allocTracker": false, "allocSnapshotInterval": 30,
    "logDirectory": "logs/vram-tweak"
  },
  "hud": { "enabled": true, "offsetX": 4, "offsetY": 4 },
  "governor": { "enabled": false, "hysteresis": 10, "minDistance": 4, "cooldownTicks": 100 }
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
├── MixinGpuDevice_VRAMOptimize      → createTexture() atlas cap (W/H) / format downscale / texture tracking
├── MixinGameRenderer_Metrics        → per-frame stats + alloc snapshots + governor trigger
├── MixinGlBuffer_Init_BufferTracker / MixinBufferStorageImmutable_BufferTracker → buffer alloc tracking (Blaze3D)
├── MixinGlBufferDirect_Close        → buffer free tracking (Blaze3D)
├── MixinGlTexture_Destroy           → texture free tracking (Blaze3D)
├── MixinGlFramebuffer_AllocTracker  → framebuffer attachment classification
├── MixinOptions_RenderDistance      → governor: ClientChunkCache cap
├── MixinOptions_EffectiveRenderDistance → governor: Options read-side cap
├── MixinGui_Hud / MixinMinecraft_Hud → HUD overlay
└── MixinGameRenderer_PerfMonitor    → AMD GPU clocks

Core (src/main)
├── allocation/              → AllocTracker: categories, tracking, logging
├── gpu/                     → VramProbe / GlVramProbe / GPUDetector / AmdVramLookup / Voxy probes
├── VRAMOptimizer / VRAMGovernor / MetricsEngine / VramFrameCounter
├── VerificationLogger / VramModLog / VRAMConfig

Client (src/client)
├── VramTweakHud / VramTweakCommand / ClothConfigFactory / ModMenuIntegration
└── AMDPerfMonitor
```

---

## License

GNU General Public License v2.0. See [LICENSE](LICENSE).
