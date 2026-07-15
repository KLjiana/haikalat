# post-v0.8 色彩、Bloom 与实例阴影基准（2026-07-15）

## 环境与方法

- GPU：NVIDIA GeForce RTX 3050 Laptop GPU，driver 576.88，OpenGL 4.6。
- 窗口：800×600 hidden window，VSync off，debug callback on。
- 每项预热 100 帧、正式采样 1000 帧，共 5 轮；奇数轮正序、偶数轮逆序。
- 表中最终值是 5 个单轮统计值的中位数，不选择最高 FPS。
- CPU 表示从动态实例快照生成到命令执行完成的 submit 时间；GPU 来自 RenderGraph timer query。
- `runDemoIntegration` 等 8 帧任务只验证功能和退出协议，其 `[INTEGRATION-ONLY]` 输出不能用于性能回退判断。

复现入口：

```powershell
.\gradlew.bat runPostV08Benchmarks
.\gradlew.bat runInstanceShadowBenchmarks
```

## LDR 与 ACES

| 场景 | present FPS | CPU avg/median ms | GPU avg/median ms | ACES GPU median 墙钟增量 |
|---|---:|---:|---:|---:|
| LDR NONE | 1204.3 | 0.163 / 0.152 | 0.060 / 0.056 | — |
| ACES NONE | 1255.4 | 0.169 / 0.157 | 0.079 / 0.078 | +0.022 ms |
| LDR MSAA | 1167.8 | 0.177 / 0.163 | 0.064 / 0.059 | — |
| ACES MSAA | 1074.6 | 0.205 / 0.193 | 0.128 / 0.123 | +0.064 ms |
| LDR FXAA | 1285.5 | 0.158 / 0.148 | 0.065 / 0.063 | — |
| ACES FXAA | 1250.2 | 0.168 / 0.156 | 0.086 / 0.085 | +0.022 ms |
| LDR TAA | 1264.8 | 0.172 / 0.159 | 0.072 / 0.071 | — |
| ACES TAA | 1218.0 | 0.185 / 0.174 | 0.135 / 0.126 | +0.055 ms |

ACES 的纯 tone-mapping 成本在 NONE/FXAA 下约 0.022 ms GPU median；MSAA 还包含浮点 resolve，
TAA 还包含 `RGBA16F` history 累积，因此两者增量更高。present FPS 的短时调度噪声会掩盖这些差异，
判断仍以 GPU/CPU 帧时间为准。

## Bloom

ACES+FXAA 开启三层 Bloom 后，整体 GPU average/median 从 `0.086 / 0.085 ms` 增加到
`0.155 / 0.151 ms`，即约 `+0.069 / +0.066 ms`。

| Bloom pass | GPU average ms | GPU median ms |
|---|---:|---:|
| BloomExtractPass（1/2） | 0.0106 | 0.0102 |
| BloomDownPass1（1/4） | 0.0075 | 0.0072 |
| BloomDownPass2（1/8） | 0.0049 | 0.0051 |
| BloomUpPass1（1/4） | 0.0074 | 0.0072 |
| BloomUpPass0（1/2） | 0.0141 | 0.0143 |

五个 Bloom level 合计约 `0.0445 ms` GPU average。最终 Bloom 保持半分辨率并直接交给 ToneMapping，
没有增加全分辨率 HDR combine target。

## 10 万动态实例阴影：触发与优化

初始双上传路径满足路线图触发条件：shadow enabled 的 CPU median 在 5/5 轮都增加超过 0.5 ms，
并且每帧实例上传从 6.104 MiB 翻倍到 12.207 MiB。

| 初始路径 | CPU avg/median ms | GPU avg/median ms | upload MiB/frame | ring wait ms/frame |
|---|---:|---:|---:|---:|
| Shadow off | 5.795 / 5.469 | 0.957 / 0.757 | 6.104 | 0.0032 |
| Shadow on | 8.915 / 8.483 | 2.776 / 2.651 | 12.207 | 0.0048 |
| 开启代价 | +3.120 / +3.014 | +1.819 / +1.894 | +6.103 | +0.0016 |

因此实现了正式的 `prepareInstancedBatch -> drawPreparedInstancedBatch -> finishPreparedInstancedBatch`
typed command。一次矩阵快照和一次 persistent-ring 上传现在可由 shadow/geometry 两个 pass 复用，fence 只在
最后一次 draw 后插入；生产路径没有使用 `custom()`。

五轮复测结果：

| 单次上传路径 | CPU avg/median ms | GPU avg/median ms | upload MiB/frame | ring wait ms/frame |
|---|---:|---:|---:|---:|
| Shadow off | 6.196 / 5.980 | 1.409 / 0.798 | 6.104 | 0.0029 |
| Shadow on | 5.927 / 5.771 | 1.718 / 1.444 | 6.104 | 0.0025 |
| 开启代价 | -0.269 / -0.209 | +0.309 / +0.646 | 0.000 | -0.0004 |

CPU 的负差值属于轮次噪声，但可以确认原来的约 3.0 ms median 双提交成本已消失；上传量严格减半，
ring wait 低于 0.003 ms/帧。阴影开启后仍有约 0.646 ms GPU median 的真实 depth draw 成本，这是应保留的
渲染工作，不应通过 CPU 上传协议隐藏。

## 结论

- 颜色空间与 ACES 的额外成本小且稳定；MSAA/TAA 的主要增量来自浮点 resolve/history，而非 gamma。
- 三层 Bloom 在本机增加约 0.066 ms GPU median，各 level 已由 RenderGraph 独立计时。
- 路线图的单次上传条件被明确触发，并已完成实现与复测；10 万实例阴影的 CPU 双提交瓶颈已移除。
- 所有结论来自正式 5×(100+1000) 帧协议，不引用短容量集成输出。
