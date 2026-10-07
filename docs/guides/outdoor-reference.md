# 旧单太阳 Outdoor 独立参考

2026-10-06。M7 删除生产旧链路前，先保存可独立编译的 0.24.3 单太阳实现。
参考引擎与当前多光源实现分开运行；用途是森林视觉连续性，不是物理多光源正确性的 oracle。
当前生产旧入口已删除，迁移与直接消费者检查见 [M7 报告](../releases/v0.25.0-m7-migration-report.md)；新旧三场景九帧无雾回归见 [无雾报告](../releases/v0.25.0-m7-nofog-report.md)。

[源码归档与原始执行身份](../../build/reports/render3d-v0250/4c2bcf372214d8ac76f061ac28140a386954b5af2c9329a9497e4b7d6a785cc9/cf654ff1-b041-40f1-8ef5-21f4282db642/manifest.json)包含：

- 运行时指纹 `0c458851cfa789a93bb7dd57db480433f32ac33dee3ba92baeed9e1817e4b213`，1,730 个运行时输入。
- [独立源码 ZIP](../../build/reports/render3d-v0250/4c2bcf372214d8ac76f061ac28140a386954b5af2c9329a9497e4b7d6a785cc9/cf654ff1-b041-40f1-8ef5-21f4282db642/outdoor-reference-source.zip)，1,735 个文件；额外保存 settings、wrapper 入口、依赖锁和 LICENSE。
- ZIP SHA-256：`b654ee6083fe6702417396a03ac4a5c63915049efdee3ade450931dfd26b4e7a`。归档内容已逐字节核对。
- 原单太阳、96 步/全分辨率/体积历史关闭、无雾各 60/240/420 帧。PNG 实际为 1920×1080；旧 HDR 导出器对每个 4×4 像素块取平均，保存的是 480×270 RGB float，不能称为完整原生 HDR。

## 运行

使用 JDK 21，在仓库根目录执行[参考运行脚本](../../tools/reference/run-outdoor-reference.ps1)：

```powershell
$env:JAVA_HOME = 'D:/java/jdk21'
./tools/reference/run-outdoor-reference.ps1 -ArchiveZip './build/reports/render3d-v0250/4c2bcf372214d8ac76f061ac28140a386954b5af2c9329a9497e4b7d6a785cc9/cf654ff1-b041-40f1-8ef5-21f4282db642/outdoor-reference-source.zip' -Mode high
```

`Mode` 为 `balanced`、`high` 或 `fog-off`。脚本验证归档 SHA，解压到新的 `build/references/` 目录，在该目录编译旧引擎，输出三张 PNG 和三份采样 HDR。
`-OutputDirectory` 可指定尚不存在的目录；`-ExtractOnly` 只解压。运行参数通过 JSON 和临时 Gradle init 文件传递，支持含空格的输出路径。
使用旧引擎已有的 baseline 抓图叶子及其最多 6 次离线读回日志规则；不修改旧规则，也不使用互动 Demo 任务作为离线抓图入口。

独立运行已在带空格路径上实际通过，9 个构建/执行任务均为新执行，墙钟 17.63s。
[复现记录](../../build/reports/render3d-v0250/4c2bcf372214d8ac76f061ac28140a386954b5af2c9329a9497e4b7d6a785cc9/cf654ff1-b041-40f1-8ef5-21f4282db642/isolated-reproduction.json)保留原时间及输出身份。
首次互动入口的日志门禁失败与首次含空格参数传递失败另行保留，不计为通过。

归档和运行脚本均在发行 `src/main` 资源之外。生产旧类/shader 清理后继续保留这套参考及历史报告；临时生产 JAR 已检查不含旧实现，最终 0.25 包仍待验收。
