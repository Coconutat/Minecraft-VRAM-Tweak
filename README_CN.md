# VRAM Tweak

[**English**](README.md) | **中文**

> Minecraft 26.2 Fabric 显存优化模组 — 诊断并降低 GPU 显存占用。

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---

<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>

***

## 概述

VRAM Tweak 通过 Mixin 注入在 OpenGL 层面拦截 GPU 纹理创建。它截断超大纹理图集、降低深度缓冲精度、限制动画帧数，并在显存紧张时动态调整渲染距离 — **全程不修改 Sodium、Iris 或任何第三方模组代码**。内置显存溯源工具 (AllocTracker)，可拦截每笔 GPU 分配并按类别和来源分类。

### 活跃功能

| 功能 | 原理 | 状态 |
|------|------|------|
| **图集尺寸上限** | 限制纹理图集宽高 ≤ `maxAtlasSize` | ✅ 稳定 |
| **深度缓冲降精度** | D32_FLOAT → D16_UNORM | ✅ 已验证 |
| **阴影贴图上限** | 限制阴影贴图分辨率 | ✅ 稳定 |
| **动画帧限制** | 截断动画纹理最大帧数 | ✅ 稳定 |
| **VRAM 调速器** | 显存紧张时自动降低渲染距离，主动执行 | ✅ 稳定 |
| **预算追踪** | 每帧轮询 VRAM 用量 + 可配置告警 | ✅ 稳定 |
| **AllocTracker** | 拦截每笔 GPU 分配/释放，按类型和来源分类 | ✅ 稳定 |
  
> ⚠️ **渲染距离降低是临时的、动态的。** 设置菜单里显示的仍是你配置的原始值。查看实际生效的渲染距离请打开 HUD 叠加层——它会实时显示调速器当前上限。显存恢复后上限自动解除。

### 条件触发功能

| 功能 | 触发条件 |
|------|---------|
| 颜色缓冲降精度 (RGBA16F→RGBA8) | 需高精度材质包或光影包触发 |

> **注意：** 在 GUI 中关闭 MOD 后，仅对**新创建的纹理**生效。已加载的纹理保持当前大小，需**重启游戏**恢复原始分辨率。

---

## AllocTracker — 显存溯源

拦截每笔 `glTexImage2D` / `glDeleteTextures` 调用，按类别和来源分类。定期输出聚合快照到 `logs/vram-tweak/mod.log`。

**示例输出：**
```
[AllocTracker] ===== Snapshot t=0s =====
  GL 已用: 4971/8192 MB (60%)
  ── 按类别 ──
    TEXTURE_ATLAS                  2772 MB  (61%)
    RENDER_TARGET_COLOR            1571 MB  (35%)
  ── 按来源 ──
    ATLAS_BLOCKS                   1536 MB  (34%)
    ATLAS_ITEMS                     768 MB  (17%)
```

通过配置 → 诊断 → 显存分配追踪器 开启，或 `/vramtweak allocreport`。

---

## HUD 叠加层

实时数据叠加，每个指标独立开关：FPS（平滑/平均/1%/0.1%）、帧时间、VRAM、Atlas 统计、分配计数、调速器状态、分配分解。

---

## 命令

```
/vramtweak stats       — VRAM + FPS 统计
/vramtweak dump        — 环形缓冲区 CSV 导出
/vramtweak hud         — 开关 HUD
/vramtweak benchmark   — VRAM 压力测试（ON vs OFF）
/vramtweak allocreport — 分配分解（按类别 + Top-10 最大纹理）
```

---

## 性能表现

**测试环境：** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
**软件：** MC 26.2 + Sodium 0.9.0 + Iris 1.11 + ScalableLux 光影 + 80+ mod

### 显存构成 (AllocTracker, maxAtlasSize=2048)

| 来源 | 大小 | 占比 |
|------|------|------|
| 纹理图集 (方块、物品、杂项) | ~2772 MB | 55% |
| Iris 渲染目标 (G-buffer) | ~1571 MB | 32% |
| 其他 (实体、GUI、字体) | ~180 MB | 4% |
| 未追踪 (驱动、SSBO) | ~448 MB | 9% |
| **总计** | **~4971 MB** | 8GB 的 60% |

> 图集上限可将单个图集从 16384px 压缩至配置上限。Iris G-buffer 是第二大显存消耗源，目前未被拦截。

---

## 依赖要求

| 依赖 | 类型 |
|------|------|
| **Sodium** | 建议 |
| Iris | 软依赖（光影兼容） |
| Cloth Config | 软依赖（GUI） |
| ModMenu | 软依赖（配置按钮） |

**平台：** Windows、Linux  
**Java：** 25+  
**GPU：** AMD（自动检测）、NVIDIA（手动启用）、Intel（安全跳过）

---

## 快速开始

1. 安装 Minecraft 26.2 的 [Fabric](https://fabricmc.net/use/)
2. 安装 [Sodium](https://modrinth.com/mod/sodium) 0.9.0
3. 安装 [Cloth Config API](https://modrinth.com/mod/cloth-config)
4. 将 `vram-tweak-*.jar` 放入 `mods/`
5. 启动 → Mod Menu → VRAM Tweak → 启用

---

## 配置文件

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

## 构建

```bash
./gradlew build
# 输出: build/libs/vram-tweak-*.jar
```

需要 JDK 25+。

---

## 架构

```
Mixin 注入层
├── MixinGpuDevice_VRAMOptimize      → createTexture() 格式/尺寸上限
├── MixinGameRenderer_Metrics        → 逐帧统计 + 分配快照 + 调速器触发
├── MixinGlStateManager_AllocTracker → glTexImage2D / glDeleteTextures 拦截
├── MixinGlFramebuffer_AllocTracker  → 帧缓冲附件追踪
├── MixinSpriteContents_Animation    → 动画帧截断
├── MixinOptions_RenderDistance      → 调速器: ClientChunkCache 区块加载上限
├── MixinOptions_EffectiveRenderDistance → 调速器: Options 读取侧上限
├── MixinGui_Hud / MixinMinecraft_Hud → HUD 叠加层
├── MixinGlStateManager_PinnedMemory → AMD 钉住内存（实验性）
└── MixinGameRenderer_PerfMonitor    → AMD GPU 频率监控

核心模块 (src/main)
├── allocation/              → AllocTracker: 类别、来源、追踪、日志
├── VRAMOptimizer / VRAMGovernor / MetricsEngine / VramFrameCounter
├── VerificationLogger / VramModLog / GPUDetector / VRAMConfig

客户端模块 (src/client)
├── VramTweakHud / VramTweakCommand / ClothConfigFactory / ModMenuIntegration
└── AMDPerfMonitor / PinnedMemory + PBO 池
```

---

## 许可证

CC0 1.0 Universal。详见 [LICENSE](LICENSE)。
