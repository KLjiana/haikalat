# Stress indexed / compact SSBO 基准（2026-07-14）

环境：NVIDIA GeForce RTX 3050 Laptop GPU，driver 576.88；OpenGL 4.6；1280×720 hidden window；VSync off；debug callback on。所有项目保持相同 clear、present 和窗口设置，每项 warmup 100 帧、正式统计 1000 帧。

| 场景                       | present FPS | CPU avg/median ms | GPU avg/median ms | draws | state skip | VS invocations |
|--------------------------|------------:|------------------:|------------------:|------:|-----------:|---------------:|
| 空窗口                      |      5723.2 |     0.026 / 0.019 |     0.007 / 0.007 |     0 |     100.0% |            N/A |
| 100k Triangle `gpu`      |      4590.2 |     0.041 / 0.033 |     0.088 / 0.088 |     1 |     100.0% |        300,000 |
| 100k Cube `gpu`（展开）      |      1497.7 |     0.051 / 0.045 |     0.620 / 0.580 |     1 |     100.0% |      3,600,000 |
| 100k Cube `indexed`      |      2729.4 |     0.052 / 0.044 |     0.308 / 0.291 |     1 |     100.0% |        900,000 |
| 100k Cube `indexed-ssbo` |      2516.4 |     0.050 / 0.044 |     0.342 / 0.382 |     1 |     100.0% |        900,000 |
| 100k Cube `dynamic`      |       236.7 |     3.801 / 3.668 |     1.380 / 1.438 |     1 |     100.0% |      2,400,000 |

## 帧时间差（average GPU）

- indexed 收益：`0.620 - 0.308 = 0.312 ms`。
- SSBO 收益：`0.308 - 0.342 = -0.034 ms`，即本次静态规则网格上回退 0.034 ms。
- 展开 Cube 相对 Triangle 的额外成本：`0.620 - 0.088 = 0.532 ms`。
- indexed Cube 相对 Triangle 的额外成本：`0.308 - 0.088 = 0.220 ms`。
- indexed-ssbo Cube 相对 Triangle 的额外成本：`0.342 - 0.088 = 0.254 ms`。

## 结论

- indexed 路径把实际 VS invocation 从 360 万降到 90 万，下降 75%。理论唯一角点数是 80 万；本机 NVIDIA post-transform cache 对当前 36-index triangle list 实测为每实例 9 次，而不是把理论 8 次当作实测值。
- indexed 与 indexed-ssbo 的 GPU average 都明显低于展开路径的约 0.58–0.62 ms；分别约快 50.3% 和 44.8%。
- compact layout 达到 16 bytes/instance：10 万实例 1.6 MB；Matrix4f 对照为 64 bytes/instance、6.4 MB。静态 compact 数据只上传一次。
- `indexed-ssbo` 没有击败无实例 buffer 的 `indexed`：规则网格可用少量位运算直接生成，SSBO 全局读取成本略高。compact SSBO 的价值在需要任意实例数据或动态 dirty-range 更新时，而不是替代完全 procedural 的规则场景。
- `dynamic` 每帧重新编码/写入 10 万个 Matrix4f，CPU median 3.668 ms，是当前最明显的 CPU 瓶颈；它保留为兼容与 A/B 基线。
