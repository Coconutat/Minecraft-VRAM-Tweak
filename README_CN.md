# VRAM Tweak

[**English**](README.md) | **中文**

> Minecraft 26.2 Fabric 取证驱动的显存优化模组 — 先诊断显存花在哪，再对经得起验证的项目做优化。

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-blue)](https://www.minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3-yellow)](https://fabricmc.net)

---

<p align="center">
  <img src="Cover.jpg" alt="VRAM Optimizer Cover" height="512" width="512"/>
</p>

***

## 概述

VRAM Tweak 通过 Mixin 注入在 Blaze3D 抽象层拦截 GPU 纹理创建。它截断超大纹理图集、降低深度缓冲精度、在显存紧张时动态调整渲染距离，并钳制不安全的 Voxy 几何缓冲上限 — **全程不修改 Sodium、Iris 或任何第三方模组代码**。核心是内置显存溯源工具 (AllocTracker)，按类别和来源分类每笔追踪到的 GPU 分配。

### 活跃功能

| 功能 | 原理 | 状态 |
|------|------|------|
| **AllocTracker** | 追踪 GPU 分配/释放，按类型和来源分类 | ✅ 核心 |
| **深度缓冲降精度** | D32_FLOAT → D16_UNORM | ✅ 已验证 |
| **图集尺寸上限** | 限制纹理图集宽高 ≤ `maxAtlasSize` | ⚠️ 已验证；部分光影下方块某一面可能变黑 |
| **VRAM 调速器** | 显存紧张时自动降低渲染距离，主动执行 | ⚠️ P2 校准中（T2：收益有限，策略待调） |
| **预算追踪** | 周期轮询 VRAM 用量 + 可配置告警 | ✅ 稳定 |
| **Voxy 几何缓冲钳制** | 防止 Voxy geometry buffer 低于安全下限（最小 1024MB） | ✅ 已验证（512 抖动；1024 安全；8GB 下 2048 更糟） |

> ⚠️ **渲染距离降低是临时的、动态的。** 设置菜单里显示的仍是你配置的原始值。查看实际生效的渲染距离请打开 HUD 叠加层——它会实时显示调速器当前上限。显存恢复后上限自动解除。

### 条件触发功能

| 功能 | 触发条件 |
|------|---------|
| 颜色缓冲降精度 (RGBA16F→RGBA8) | 需高精度材质包或光影包触发 |

> **注意：** 在 GUI 中关闭 MOD 后，仅对**新创建的纹理**生效。已加载的纹理保持当前大小，需**重启游戏**恢复原始分辨率。

---

## AllocTracker — 显存溯源

在 Blaze3D 抽象层拦截 GPU 资源创建/释放（`GpuDevice.createTexture`、`GlTexture.destroyImmediately`、`GlBuffer`/`BufferStorage` 生命周期），按类别和来源分类。定期输出聚合快照到 `logs/vram-tweak/mod.log`。

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
/vramtweak allocreport — 分配分解（按类别 + Top-10 最大纹理 + GL−追踪差额趋势）
/vramtweak phase <name> — 在 mod.log 与 verify 日志中标记测试阶段
/vramtweak probe       — 即时输出 VRAM 探测详情（ATI 池、Voxy）
/vramtweak governor on|off|status — 快速开关/查询 Governor
```

---

## 性能表现

**测试环境：** AMD R5 5600 + 32GB + RX 6650 XT 8GB  
**软件：** MC 26.2 + Sodium 0.9.1 + Iris 1.11.2 + ComplementaryReimagined_r5.8.1 + EuphoriaPatches + Voxy 0.2.18-beta + 80+ mod

### 2026-08-15 真实日志显存构成（AllocTracker）

| Voxy geometry limit | 峰值（VBO 口径） | 追踪 | 未追踪 | 说明 |
|---|---|---|---|---|
| 512MB（旧会话） | 8028/8192 MB (97%) | — | — | Voxy 节点层级抖动 |
| 1024MB, atlas 8192 | 7576/8192 MB (92%) | 3118 MB | 4458 MB | 无抖动；压力 = Voxy + 三张 8192² 图集 + 光影包 |
| 2048MB, atlas 8192 | 8028/8192 MB (97%) | 2993 MB | 5035 MB | 多 1GB geometry 直接压到边缘 |
| **1024MB, atlas 4096** | **6723/8192 MB (82%)** | **1895 MB** | **4828 MB** | **推荐 P2 基线** |

1024 + atlas 8192 时追踪侧大头是**三张 8192² 图集**（`blocks`、`blocks_n`、`blocks_s`，各约 341MB 含 mip，共约 1023MB）+ Sodium buffer geometry（约 750MB）。**atlas 4096 时图集合计降到约 944MB**。未追踪侧大头是 **Voxy raw GL**（geometry ~1GB + model atlas ~0.5GB）和 **Iris 光影 raw GL**（约 2.5–2.9GB）。

> **建议：Voxy geometry limit 保持 1024MB，`maxAtlasSize` 用 4096。** 512 会抖动，2048 在 8GB 卡上更糟；1024+4096 是目前实测最佳组合。

> **VRAM 调速器现状（P2）：** 第一轮 T2 中开启后峰值从 ~83% 降到 ~82%（约省 90MB），但日志出现 12↔6 慢速锯齿；策略正在调优（恢复稳定窗口）。完整证据见 `Docs/p2-plan.md`。

### 2026-08-16 光影阴影分辨率实测（8GB 卡，测试用光影包）

| `shadowMapResolution` | 峰值 GL（第一轮） | 未追踪（第一轮） |
|---|---|---|
| 1024 | 6813/8192 MB (83%) | ~4830 MB |
| 2048 | 6943/8192 MB (84%) | ~4909 MB |
| 4096 | 7387/8192 MB (90%) | ~5180 MB |

同日二次复测（12:42–12:47）同三档约 6866 / 7033 / 7544 MB（83 / 85 / 92%），差 1–2% 属于场景/加载波动。不同光影包的阴影实现不同，数字不可直接套用；该测试包在 8GB 卡上推荐 `shadowMapResolution=1024`。

### VRAM 查询口径

实测 AMD Windows 驱动下，`GL_ATI_meminfo` 三个池 token 启动期返回同一个值，运行期 texture/renderbuffer 可能返回垃圾值。**计算只用 `GL_VBO_FREE_MEMORY_ATI`（0x87FB）**，另外两个池只作取证记录；这张卡上**不能三池求和**。

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
  "governor": { "enabled": false, "hysteresis": 10, "minDistance": 4, "cooldownTicks": 100, "restoreStableMs": 30000, "restoreCeiling": 0 }
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
├── MixinGpuDevice_VRAMOptimize      → createTexture() 图集截断（宽+高）/ 格式降精度 / 纹理追踪
├── MixinGameRenderer_Metrics        → 逐帧统计 + 分配快照 + 调速器触发
├── MixinGlBuffer_Init_BufferTracker / MixinBufferStorageImmutable_BufferTracker → 缓冲分配追踪（Blaze3D 层）
├── MixinGlBufferDirect_Close        → 缓冲释放追踪（Blaze3D 层）
├── MixinGlTexture_Destroy           → 纹理释放追踪（Blaze3D 层）
├── MixinGlFramebuffer_AllocTracker  → 帧缓冲附件分类
├── MixinOptions_RenderDistance      → 调速器: ClientChunkCache 区块加载上限
├── MixinOptions_EffectiveRenderDistance → 调速器: Options 读取侧上限
├── MixinGui_Hud / MixinMinecraft_Hud → HUD 叠加层
└── MixinGameRenderer_PerfMonitor    → AMD GPU 频率监控

核心模块 (src/main)
├── allocation/              → AllocTracker: 类别、来源、追踪、日志
├── gpu/                     → VramProbe / GlVramProbe / GPUDetector / AmdVramLookup / Voxy 探测
├── VRAMOptimizer / VRAMGovernor / MetricsEngine / VramFrameCounter
├── VerificationLogger / VramModLog / VRAMConfig

客户端模块 (src/client)
├── VramTweakHud / VramTweakCommand / ClothConfigFactory / ModMenuIntegration
└── AMDPerfMonitor
```

---

## 许可证

GNU General Public License v2.0。详见 [LICENSE](LICENSE)。
