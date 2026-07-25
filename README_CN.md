# VRAM Tweak

[**English**](README.md) | **中文**

> Minecraft 1.21.11 / 26.2 Fabric 显存优化模组 — 诊断并降低 GPU 显存占用，无需修改着色器或资源包。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11_|_26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---
  
<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>
  
***

## 概述

VRAM Tweak 通过 Mixin 注入在 OpenGL 层面拦截 GPU 纹理创建。它截断超大纹理图集、限制动画帧数、在显存紧张时动态调整渲染距离，并追踪每笔 GPU 分配按类别/来源分类——**全程不修改 Sodium、Iris 或任何第三方模组代码**。

### 活跃功能

| 功能 | 原理 | 状态 |
|------|------|------|
| **图集尺寸上限** | 限制纹理图集宽高 ≤ `maxAtlasSize` | ✅ 稳定 |
| **阴影贴图上限** | 限制阴影贴图分辨率 | ✅ 稳定 (需 Iris) |
| **动画帧限制** | 截断动画纹理最大帧数 | ✅ 稳定 |
| **VRAM 调速器** | 显存紧张时自动降低渲染距离 | ✅ 稳定 |
| **预算追踪** | 每帧轮询 VRAM 用量 + 可配置告警 | ✅ 稳定 |
| **AllocTracker** | 拦截每笔 GPU 分配/释放，按类型和来源分类 | ✅ 稳定 |

### 休眠功能（1.21.11 无触发源）

| 功能 | 原因 |
|------|------|
| 颜色缓冲降精度 (RGBA16F→RGBA8) | MC 1.21.11 `TextureFormat` 仅有 RGBA8 |
| 深度缓冲降精度 (D32→D16) | MC 1.21.11 仅有 DEPTH32 |

> **注意：** 在 GUI 中关闭 MOD 后，仅对**新创建的纹理**生效。已加载的纹理保持当前大小，需**重启游戏**恢复原始分辨率。

---

## AllocTracker — 显存溯源

在 OpenGL 层面拦截每笔 `glTexImage2D` / `glDeleteTextures` 调用，按类别和来源分类。定期输出聚合快照到 `logs/vram-tweak/mod.log`。

**示例输出：**
```
[AllocTracker] ===== Snapshot t=0s =====
  GL 已用: 4971/8192 MB (60%)
  追踪已用: 4523 MB  |  未追踪: 448 MB
  ── 按类别 ──
    TEXTURE_ATLAS                  2772 MB  (61.3%)
    RENDER_TARGET_COLOR            1571 MB  (34.7%)
  ── 按来源 ──
    ATLAS_BLOCKS                   1536 MB  (34.0%)
    ATLAS_ITEMS                     768 MB  (17.0%)
```

通过配置 → 诊断 → 显存分配追踪器 开启，或在游戏内运行 `/vramtweak allocreport`。

---

## HUD 叠加层

实时性能数据叠加显示，每个指标独立开关：

- FPS（平滑/平均/1% Low/0.1% Low）、帧时间
- VRAM 已用/总量 + 颜色编码百分比
- Atlas 统计、纹理分配计数、降精度触发次数
- Budget 告警状态、调速器状态、分配分解

---

## 命令

```
/vramtweak stats       — 聊天栏输出 VRAM + FPS 统计
/vramtweak dump        — 写入环形缓冲区 CSV 到磁盘
/vramtweak hud         — 开关 HUD 叠加层
/vramtweak benchmark   — VRAM 压力测试（ON vs OFF 对比）
/vramtweak allocreport — 输出分配分解（按类别 + Top-10 最大纹理）
```

---

## 性能表现

**测试环境：** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
MC 1.21.11 + Sodium 0.8.12 + Iris 1.10.7 + ScalableLux 光影 + 80+ mod

### 显存构成 (AllocTracker, maxAtlasSize=2048)

| 来源 | 大小 | 占比 |
|------|------|------|
| 纹理图集 (方块、物品、杂项) | ~2772 MB | 55% |
| Iris 渲染目标 (G-buffer) | ~1571 MB | 32% |
| 其他 (实体、GUI、字体) | ~180 MB | 4% |
| 未追踪 (驱动开销、SSBO) | ~448 MB | 9% |
| **GL 报告总计** | **~4971 MB** | 8GB 的 60% |

> 图集上限可将单个图集从 16384px 压缩至配置上限。Iris 光影渲染目标 (G-buffer) 是第二大显存消耗源，目前未被拦截。

---

## 快速开始

1. 安装 [Fabric](https://fabricmc.net/use/)
2. 安装 [Sodium](https://modrinth.com/mod/sodium)
3. 安装 [Cloth Config API](https://modrinth.com/mod/cloth-config)（GUI 配置）
4. 将 `vram-tweak-*.jar` 放入 `mods/` 目录
5. 启动游戏 → Mod Menu → VRAM Tweak → 启用功能

---

## 配置文件

所有设置位于 `config/vram-tweak.json`。使用 Cloth Config GUI（Mod Menu → VRAM Tweak）交互式配置。

```jsonc
{
  "version": 1,
  "showExperimental": false,
  "vram": {
    "enabled": true,
    "shadowCapEnabled": true,
    "shadowMapMaxSize": 1024,
    "formatDownscale": false,     // 1.21.11 休眠
    "depthDownscale": false,      // 1.21.11 休眠
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

## 构建

```bash
./gradlew build
# 输出: build/libs/vram-tweak-*.jar
```

需要 JDK 21+。

---

## 架构

```
Mixin 注入层
├── MixinGpuDevice_VRAMOptimize      → createTexture() 格式/尺寸上限
├── MixinGameRenderer_Metrics        → 逐帧统计 + VRAM 轮询 + 分配快照
├── MixinGlStateManager_AllocTracker → glTexImage2D / glDeleteTextures 拦截
├── MixinGlFramebuffer_AllocTracker  → 帧缓冲附件追踪
├── MixinSpriteContents_Animation    → 动画帧截断
├── MixinOptions_RenderDistance      → 调速器钩子
├── MixinGui_Hud / MixinMinecraft_Hud → HUD 叠加层
└── MixinGlStateManager_PinnedMemory → AMD 钉住内存（实验性）

核心模块 (src/main)
├── allocation/              → AllocTracker: 类别、来源、追踪、日志
├── VRAMOptimizer            → 格式/尺寸策略引擎
├── VRAMGovernor             → 动态渲染距离控制器
├── MetricsEngine            → 环形缓冲区性能采样
├── VramFrameCounter         → 滑动窗口 FPS + 百分位低帧率
├── VerificationLogger       → 优化前后审计追踪
├── VramModLog               → 集中式文件日志 (logs/vram-tweak/mod.log)
├── GPUDetector              → GPU 检测 + VRAM 查询
└── VRAMConfig               → Gson 配置

客户端模块 (src/client)
├── VramTweakHud             → 单例叠加层渲染器
├── VramTweakCommand          → /vramtweak CLI (stats/dump/hud/benchmark/allocreport)
├── ClothConfigFactory       → GUI 集成
└── ModMenuIntegration       → Mod Menu 入口
```

---

## 许可证

CC0 1.0 Universal。详见 [LICENSE](LICENSE)。
