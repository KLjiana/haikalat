# v0.27.0 实际 Minecraft 回调验收

这是 HaikalatHost 的 opt-in 验收消费者，不属于默认游戏功能。基于宿主提交 `2e9ba7ff3fbf6497a4e718d6cddfd77000a80404`，Minecraft 1.21.1 / NeoForge 21.1.215 / Java 21。只在隔离工作树和复制的测试存档运行。

`host-acceptance.patch` 包含消费者、注册点和运行属性；`git apply --check` 后在该宿主提交应用。准备两个复制存档，分别命名为 `V0270AcceptanceSafe`、`V0270Acceptance2`，保留原始存档。消费者把测试玩家设为旁观者；本范围使用标准投影和 `bobView:false`，不覆盖受伤/摇晃的斜投影。测试 options 还应设 `onboardAccessibility:false`、`pauseOnLostFocus:false`、`fullscreen:false`，避免未完成的新客户端引导。

在宿主工作树创建新的 `build/reports/v0270-host/runtime`，将 `control.txt` 写为 `1 RESIZE`。已有证据先重命名保留，不能把旧 events 或截图混入本轮。运行（路径按实际位置替换）：

```powershell
.\gradlew.bat :HaikalatHost:runClient -Pv0270Acceptance=true -PhaikalathostVerifyGlState=true -PhaikalathostQuickPlayWorld=V0270AcceptanceSafe '-Pv0270EngineJar=D:/project/Java/learnopengl/build/libs/haikalat-0.25.0.jar' --init-script D:/project/Java/learnopengl/tools/acceptance/v0270-host/local-engine.init.gradle --no-daemon --console=plain
```

客户端启动后在另一终端运行下列命令，脚本会等待真实回调的第一张截图，再执行 17 个操作；超时/FAIL 会直接失败，不自动重试：

```powershell
python D:/project/Java/learnopengl/tools/acceptance/v0270-host/drive.py --runtime D:/project/Java/Project-Crystal-Fracture-v0270-host-acceptance/build/reports/v0270-host/runtime
```

检查 `events.csv`、17 项 `operation-results.json`、截图及 Gradle 退出码。CSV 的 `estimatedBytes` 是全局追踪估算，`ownedLive` 仅统计该消费者在回调中观察到的新资源；不要把它写成 Minecraft 总显存。`detail` 的 allocation 是管线自身的保守预算快照。记录实际 JAR SHA256，并核对 Host 展开资源中的 RenderPipeline.class 和 Shader；仅修改依赖声明不能证明运行的是候选。

脚本调用 Minecraft 的正常世界退出、资源重载和关闭 API，通过真实渲染回调绘制及抓取借用 framebuffer；这是实际客户端自动化验收，不是人工操作验收。结论与已知限制见 [阶段报告](../../../docs/releases/v0.27.0-phase1-review.md)。

消费者创建独立的空几何 Scene 和自设方向光，没有接入 Minecraft 地形、光源或阴影数据。本工具验证真实回调、目标借用与生命周期链；截图不能证明与 Minecraft 世界深度、遮挡及光照的统一视觉整合。2026-11-06 的范围澄清仅复核已有记录，未重新启动客户端；原补丁和归档身份保持不变。
