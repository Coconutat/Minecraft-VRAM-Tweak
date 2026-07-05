# VRAM Tweak 功能开启对比
***
语言: **简体中文** | [English](README.md)
***
本文档用于展示开启 `VRAM Tweak` 后的显存优化效果。所有图片均来自相同场景、相同材质包、相同 Shader 环境下的对比测试。

---

## 测试环境

- CPU: AMD Ryzen 5 5600
- 内存: 32GB 3200MT/s DDR4
- GPU: AMD Radeon RX6650XT
- Minecraft: v1.21.11 / 26.2 Fabric
- 资源包: EXTREAL 0.0.2085
- Shader: Spring Shader (Chun v2), iterationRP Alpha, Helian-MMCO_r0.7.1

---

## 说明

- `On` 表示已启用 `VRAM Tweak` 的 VRAM 优化功能。
- `Off` 表示未启用优化，保持原始渲染状态。
- 主要对比点是：显存占用、FPS、画面细节与光照表现是否保持稳定。
- 对比图中的左上角 HUD 里会显示显存使用情况，方便直观比较。

---

## 对比结果

### Spring Shader

- `Spring-Off.png`：关闭优化
![Spring Off](imgs/Spring-Off.png)
- `Spring-On.png`：开启优化
![Spring On](imgs/Spring-On.png)

### iterationRP

- `itRP-Off.png`：关闭优化
![iterationRP Off](imgs/itRP-Off.png)
- `itrp-On.png`：开启优化
![iterationRP On](imgs/itrp-On.png)

### Helian-MMCO

- `Helian-MMCC-Off.png`：关闭优化
![Helian-MMCO Off](imgs/Helian-MMCC-Off.png)
- `Helian-MMCO-On.png`：开启优化
![Helian-MMCO On](imgs/Helian-MMCO-On.png)

---