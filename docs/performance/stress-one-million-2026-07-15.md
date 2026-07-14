# Stress 百万实例基准（2026-07-15）

本次基准将 StressDemo 从 10 万实例提升到 100 万实例，用于放大展开式 procedural、indexed、
compact SSBO 和动态 Matrix4f 路径之间的吞吐差异。

## 环境与方法

- GPU：NVIDIA GeForce RTX 3050 Laptop GPU，driver 576.88，OpenGL 4.6。
- 窗口：1280×720 hidden window；VSync off；debug callback on。
- 每个场景预热 100 帧，正式统计 1000 帧，共运行 3 轮。
- 奇数轮按 Triangle、展开 Cube、Indexed、Indexed+SSBO、Dynamic 执行；偶数轮反向执行。
- 最终值取三个单轮统计值的中位数，而不是取最高 FPS。
- CPU 时间表示引擎 submit；GPU 时间来自 timer query；present FPS 包含 swap 和系统调度成本。

正式采样前，五条路径均通过 100 万实例短容量测试，没有发生 OOM、GPU fence 超时或 OpenGL 错误。

## 最终结果

| 场景 | present FPS | CPU avg/median ms | GPU avg/median ms | draws | state skip | VS invocations |
|---|---:|---:|---:|---:|---:|---:|
| 1M Triangle `gpu` | 672.9 | 0.060 / 0.051 | 0.784 / 0.671 | 1 | 100.0% | 3,000,000 |
| 1M Cube `gpu`（展开） | 113.3 | 0.121 / 0.111 | 7.951 / 6.786 | 1 | 100.0% | 36,000,000 |
| 1M Cube `indexed` | 208.1 | 0.127 / 0.113 | 3.926 / 3.084 | 1 | 100.0% | ≈9,000,000 |
| 1M Cube `indexed-ssbo` | 210.9 | 0.105 / 0.087 | 3.921 / 3.089 | 1 | 100.0% | 9,000,000 |
| 1M Cube `dynamic` | 10.2 | 80.771 / 75.071 | 16.671 / 15.213 | 1 | 100.0% | 24,000,000 |

## 三轮原始统计

以下序列按第 1～3 轮排列。

| 场景 | present FPS | CPU average ms | CPU median ms |
|---|---|---|---|
| Triangle `gpu` | 672.9, 666.6, 678.4 | 0.060, 0.064, 0.058 | 0.051, 0.054, 0.050 |
| Cube `gpu` | 113.5, 113.3, 111.9 | 0.121, 0.126, 0.119 | 0.111, 0.114, 0.109 |
| Cube `indexed` | 208.1, 207.0, 210.8 | 0.127, 0.144, 0.092 | 0.113, 0.144, 0.086 |
| Cube `indexed-ssbo` | 207.5, 212.7, 210.9 | 0.143, 0.105, 0.093 | 0.135, 0.087, 0.087 |
| Cube `dynamic` | 10.1, 10.2, 10.4 | 81.752, 80.771, 79.948 | 78.042, 75.071, 73.876 |

| 场景 | GPU average ms | GPU median ms | VS invocations |
|---|---|---|---|
| Triangle `gpu` | 0.778, 0.791, 0.784 | 0.668, 0.671, 0.671 | 3,000,000 × 3 |
| Cube `gpu` | 7.951, 7.947, 8.050 | 6.748, 6.786, 6.931 | 36,000,000 × 3 |
| Cube `indexed` | 3.926, 3.884, 3.961 | 3.084, 3.083, 3.354 | 8,999,996; 9,000,000; 8,999,996 |
| Cube `indexed-ssbo` | 3.904, 3.921, 3.959 | 3.089, 3.083, 3.354 | 9,000,000 × 3 |
| Cube `dynamic` | 16.718, 16.671, 16.040 | 15.468, 15.213, 14.412 | 24,000,000 × 3 |

Indexed 的两轮 pipeline statistics query 比理论值少 4 次，偏差仅约 0.000044%；报告保留驱动返回的
原始数字，最终表记为约 900 万，不把 8,999,996 改写成伪造的精确测量值。

## 帧时间差

按最终 GPU average 计算：

- indexed 收益：`7.951 - 3.926 = 4.025 ms`，相对展开 Cube 降低约 50.6%。
- SSBO 收益：`3.926 - 3.921 = 0.005 ms`，属于测量噪声，两条 indexed 路径持平。
- 展开 Cube 相对 Triangle 的额外成本：`7.951 - 0.784 = 7.167 ms`。
- indexed Cube 相对 Triangle的额外成本：`3.926 - 0.784 = 3.142 ms`。
- indexed-ssbo Cube 相对 Triangle 的额外成本：`3.921 - 0.784 = 3.137 ms`。

按 GPU median 计算，indexed 收益为 `6.786 - 3.084 = 3.702 ms`。百万实例下平均值高于
中位数，说明正式统计期间仍存在少量 GPU 尖峰，因此同时保留两种统计值。

## 与 10 万实例对比

| 路径 | 10 万 GPU average ms | 100 万 GPU average ms | 放大倍数 |
|---|---:|---:|---:|
| Triangle `gpu` | 0.069 | 0.784 | 11.4× |
| Cube `gpu` | 0.733 | 7.951 | 10.8× |
| Cube `indexed` | 0.309 | 3.926 | 12.7× |
| Cube `indexed-ssbo` | 0.308 | 3.921 | 12.7× |
| Cube `dynamic` | 1.234 | 16.671 | 13.5× |

Dynamic 的 CPU median 从 3.398 ms 增至 75.071 ms，实例数增加 10 倍、CPU 时间却增加约
22.1 倍。这条路径每帧写入 100 万个 Matrix4f，即 64 MB 实例数据；三槽 persistent ring 约占
192 MB，同时命令缓冲区每帧需要持有 100 万个矩阵快照。额外的对象遍历、分配与垃圾回收使其呈现
超线性增长。相比之下，compact SSBO 为 16 bytes/instance，100 万静态实例仅 16 MB，并且只上传一次。

## 结论

- 100 万实例已能稳定拉开路径差异：展开 Cube 约 113 FPS，两个 indexed 路径约 208–211 FPS。
- EBO/post-transform cache 将 Cube VS invocation 从 3600 万降至约 900 万，减少约 75%。
- compact SSBO 在静态场景中没有比无实例 buffer 的 indexed 更快，但也没有可测量的回退；它用
  16 MB 保存任意实例属性，是通用性与带宽之间更合理的路径。
- Dynamic Matrix4f 的瓶颈首先在 CPU 数据准备与复制，其次才是 2400 万 VS 调用；百万实例下不适合作为
  实时主路径，应保留为兼容性和 A/B 对照。
- 所有路径仍保持单 draw，稳定状态阶段 state-cache skip 为 100%。

## 默认负载调整

`StressDemo` 无参数默认值、`runStressDemo` 的 `stressInstances` 默认值，以及所有
`runStress*Demo` 性能快捷入口均已改为 100 万实例。`localGlVerification` 的两帧正确性集成仍固定
为 10 万实例，避免把日常回归测试变成长时间性能任务。
