# 实例化阴影与 HDR 后处理实施计划

> 已归档：v0.8 历史实施与验收记录，不再作为当前待办。

状态：已完成（2026-07-15）。实例阴影、HDR/ACES、四种 AA 数据流、resize 和真实 GL
像素验收均已落地；延后项仍按本文边界保留在后续评估中。

## 总结

后续工作分成两个独立里程碑：

1. `Instanced Shadow Caster`：让现有实例批次参与方向光阴影 pass。
2. `HDR + Tone Mapping`：建立线性 HDR 场景、抗锯齿、色调映射和最终呈现的完整链路。

两个里程碑完成并通过验收后，项目版本进入 `0.8.0-SNAPSHOT`。本阶段不引入 PBR、ECS、
新的模型抽象、第二套实例渲染实现或第二渲染后端。

## 里程碑 4：实例化阴影

- 为 `InstancedRenderer` 增加显式 `castShadows` 配置。保留旧构造函数并默认 `false`，避免已有调用方
  突然承担额外阴影开销。
- 增加 instanced depth-only vertex shader，复用现有 location 3～6 的 `mat4` 实例属性和
  `uLightSpace`，不增加 fragment 或 material 逻辑。
- shadow pass 先绘制普通 `SceneObject`，再用同一个 `InstancedMeshBatch` 和同一帧 transforms
  提交实例阴影。
- shadow 和 geometry 各执行一次现有 `drawInstancedBatch` 安全生命周期。本阶段允许重复上传，确保
  每次提交都有独立 fence 和异常清理。
- 不创建 shadow 专用 batch，不重复计算实例 transform，不改变公共快照语义。
- 增加实例阴影数量统计；现有普通 caster draw count 的语义保持不变。
- `LearnOpenGlDemo` 显式启用实例阴影，并确保实例能够在现有地面或接收物上产生可观察阴影。
- 双 pass 单次上传、primitive arena 和 `prepare/draw/finalize` 命令协议留给后续性能评估。

## 里程碑 5：HDR 与 Tone Mapping

### 公共配置

- 新增 `ToneMappingMode { NONE, ACES }`。
- `RenderSettings.Builder` 新增 `toneMappingMode(...)` 和 `exposure(float)`。
- exposure 只接受有限且大于零的值。
- 默认使用 `ToneMappingMode.NONE`，保持旧调用方的 LDR 画面和 pass 计划。
- `LearnOpenGlDemo` 显式启用 `ACES`，默认 exposure 为 `1.0`。
- 保留现有 `passNamesFor(...)`，增加包含 tone-mapping mode 的重载。

### RenderGraph 数据流

HDR 开启后的 pass 顺序固定如下：

| AA 模式 | Pass 顺序 |
| --- | --- |
| NONE | Geometry HDR → ToneMapping LDR → Present |
| MSAA | Geometry HDR MSAA → HDR Resolve → ToneMapping LDR → Present |
| FXAA | Geometry HDR → ToneMapping LDR → FXAA → Backbuffer |
| TAA | Geometry HDR → TAA HDR → ToneMapping LDR → Present |

具体实现要求：

- HDR geometry 使用 `RGBA16F`，tone-mapped target 使用 `RGBA8`。
- 修正 framebuffer 格式元数据，使 `RGBA16F` 使用 `GL_RGBA/GL_FLOAT`。
- MSAA 使用 `RGBA16F` renderbuffer，并单独 resolve 到可采样的 `RGBA16F` texture。
- TAA 在线性 HDR 空间累积，history 使用 `RGBA16F`；resize 后重建 history 并清除有效状态。
- FXAA 位于 tone mapping 之后，在显示空间的 LDR 图像上运行。
- ACES shader 依次执行 exposure、ACES fitted curve、clamp 和显式 gamma 编码。
- tone-mapping pass 复用现有 `ScreenQuad`、`CommandBuffer` 和 RenderGraph attachment 查询，
  不自行管理 framebuffer。
- LDR 模式继续使用现有路径，本阶段不无条件改变所有调用方的渲染结果。

## 测试与验收

### 纯 JVM

- 实例 renderer 默认不投射阴影，显式开启后才进入 shadow 路径。
- instanced shadow shader 的矩阵属性位置与现有 batch layout 一致。
- 四种 AA 模式在 LDR 和 HDR 下生成准确的 pass 顺序。
- exposure 拒绝零、负数、`NaN` 和无穷值。
- `RGBA16F` descriptor 使用正确的 internal format、external format 和 data type。
- ACES 参考计算保持有限、单调，不同 HDR 亮度不会被简单硬截断为同一结果。

### 真实 GL

- 开启实例阴影时最终像素发生可重复变化；关闭后阴影消失，但实例 geometry 仍正常绘制。
- shadow 和 geometry 连续提交同一个 batch 后，下一帧仍能正常复用 ring，不产生 GL error、
  漏 fence 或重复释放。
- HDR target 能保存大于 `1.0` 的线性颜色。
- tone mapping 输出处于有效 LDR 范围，改变 exposure 会改变最终像素。
- NONE、MSAA、FXAA、TAA 四条 HDR 路径均产生非空最终画面。
- MSAA resolve、TAA history 和 resize 后资源重建正确；固定 2048×2048 shadow target 不受窗口
  resize 影响。
- 最终执行 `compileJava demoClasses test`、相关 GL smoke 和 `localGlVerification`。

## 文档与交付

- 更新 capability matrix，将实例化阴影和 HDR/ACES 标记为完整能力。
- 更新 render boundaries，记录实例阴影双提交边界和 HDR pass 数据流。
- 更新 testing guide、future plans 和 changelog。
- 两个里程碑分别提交；全部验收通过后将版本调整为 `0.8.0-SNAPSHOT`。
- 单次上传多 pass 复用、自动曝光、Bloom 和 PBR 不在本阶段。

## 默认决策

- 采用兼容优先策略：框架默认保持 LDR，主 Demo 显式启用 ACES HDR。
- 实例阴影采用双提交方案，优先复用现有安全命令和 fence 生命周期。
- 只有后续基准证明双提交成为真实瓶颈，才设计单次上传、多 pass 复用协议。
