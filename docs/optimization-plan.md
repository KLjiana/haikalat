# Haikalat 架构与性能调查记录

## 当前结论

v0.21.0-beta 的场景提交回退已由 v0.21.1 解决。当前性能不再是 `5.6～7.5 ms`；该范围只属于
2026-08-06 的原始调查样本，不得作为当前状态引用。正式 v0.21.1 同机结果中，10,000 对象
programmatic large 为 `0.552 ms`，10,000 glTF mostly-hidden 为 `1.509 ms`，稳定帧分配约
`2.6 KiB/frame`。完整环境、场景和各组数据见
[`releases/v0.21.1-release-report.md`](releases/v0.21.1-release-report.md)。

## 已解决项

v0.21.1 采用以下内部优化恢复并超过 v0.20 基线，未改变公共 API：

1. 缓存 SceneFrame 所需的 shadow light 查询；
2. 以集合和前缀表替代 frame-owned uniform 的长字符串比较链；
3. 移除提交热路径中的 Stream/lambda 分配；
4. 合并 topology signature 的灯光遍历并允许提前退出；
5. 缓存 scene topology signature，同拓扑替换继续走 fast path。

v0.22.1 进一步为可变灯光增加 `Scene.lightingRevision()` 回归和真实 GL cache invalidation
证明，避免 topology 不变时沿用过期方向光，同时确认不重建无关 RenderGraph target。

## 历史调查数据（保留，不代表当前性能）

最初的 v0.21 调查记录如下，用于解释为什么启动专项优化：

| 样本 | 当时观测 |
| --- | ---: |
| v0.20.0 static-all-visible | `2.761 ms` |
| v0.21.0-beta 报告 | `3.508 ms` |
| 未受控的补充样本 | `5.6～7.5 ms` |

最后一组受 CPU 频率、后台负载、预热和测量口径影响，只是问题定位输入。v0.21.1 使用固定场景、
预热、轮次与 allocation 统计重新验收，因此后续比较必须使用正式报告的同口径数据。

## 当前基准策略

- correctness 先由 JVM、真实 GL、资源生命周期和 GL debug policy 门禁证明；
- 性能任务固定场景、分辨率、warmup、samples 与 rounds，并记录完整硬件/驱动身份；
- 同环境超过 5% 的回退需要复测和 profile，跨机器结果不可直接作为硬门槛；
- JFR、ThreadMXBean allocation 与 GPU timer 分别用于定位，不混写为同一个指标；
- v0.22 文本路径使用 `runTextBenchmarks` 覆盖 Markdown parse、1k/10k 冷/热布局、字体切换、
  glyph atlas、普通文字与所有现有特效，以及 1080p/4K 稳定帧。

## 未来优化候选

以下均为有条件候选，不是版本承诺或既定路线：

- 空间分区、GPU culling、indirect submission；
- 延迟渲染、SSAO、SSR；
- bindless texture、texture array、虚拟纹理；
- Vulkan 或其他第二后端；
- 动态全局光照。

候选只有在现有 OpenGL 路径出现可复现瓶颈、具备对照基线并能保持 public/architecture 合同后，
才进入具体版本计划。

---

最后更新：2026-08-09
