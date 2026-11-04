# 林境远征 · VLS 2.0 Demo

基于用户提供的 `Modpack_VLS_2.0.md`，以 Haikalat 原生窗口、RenderGraph、UiSystem 实现的独立交互原型。无需 Minecraft 或浏览器。

## 运行

在项目根目录执行：

```powershell
.\gradlew.bat runVlsDemo
```

默认窗口 1440 × 900，允许调整大小，最小 1100 × 720。使用项目现有 Java 21 toolchain。

- 点击横向旅程节点查看同名详情；锁定任务可查看原因，但不能执行。
- 「穿过云杉林」→「追踪这段旅途」→「模拟抵达石桥」：完成任务并解锁「山脊上的信号」。
- 右上角切换远征日志／随身物品；选择物品查看用途、数量和来源。
- 底部环境按钮循环切换云杉林、雪原、暗洞和熔岩谷。
- 「背景：柔焦／清晰」实时切换真正的两遍高斯模糊；文字和控件始终保持清晰。
- 「动效：减少」取消详情位移、结构构筑及缓慢视点移动，保留即时状态反馈。
- Tab 切换焦点，Enter 或空格操作按钮，Esc 退出。

## 设计与实现

- 按用户提供的「原野」参考重新组织为通透的标定层：去掉大块黑面板与实心金按钮，使用锚角、细轨、横向节点与半透明描边控件。主任务区填充 alpha 为 20/255，详情为 28/255；仅说明文字附近使用 77/255 的局部衬幕。
- 世界先绘制到半分辨率 RGBA16F 纹理，经过仅处理世界衬幕与色调的 display-stage pass，作为显式 `VlsPreUiColor` 交给 `UiSystem` 公共背景模糊。全屏背景节点使用 20 个逻辑像素半径、2 倍降采样；文字与控件绘制在背景层之后。清晰模式将半径设为零，公共滤波不记录绘制。Demo 不再维护独立高斯滤波算法。环境为项目原创程序化方块地景，未使用 Minecraft 资源。
- 暖金用于选中短线和主要操作短线；完成使用绿色与勾选，锁定使用绘制的锁图标和原因。焦点为轮廓，选中为持续顶部标记，Hover 为中性轮廓。
- 详情来自旅程中的同名任务；200 ms 位移和三阶段短轨构筑在新选择时重定向，不排队。静态仍保留布局及状态信息。五个物品格槽通过原生 UI 图元绘制物品图标，点击可进入行囊详情。
- 中文由内建 Noto Sans SC 与 Haikalat 文本系统渲染；按钮使用真实 retained input、pointer capture、focus 和 keyboard 路径。
- 世界和 UI 绘制到显式 `SRGB8_ALPHA8` 呈现目标，再复制到窗口。避免通用默认目标的 `RGBA8` 描述造成 UI 色彩变暗；不修改引擎通用实现。
- Demo 只定义世界绘制、显示衬幕和业务 UI；合成、滤波、目标预算及资源生命周期由公共 UI/RenderGraph 链路负责。

这是视觉与交互 Demo：任务抵达为明确标注的模拟操作，行囊为静态样例数据；未接入 Minecraft 存档、角色移动或实际任务系统。环境切换用于可读性预览，不代表四个完整关卡；最后两个旅程节点展示锁定状态。没有实现完整 HUD／地图体系。

## 自动验证与截图

```powershell
.\gradlew.bat runVlsIntegration assetManifestGuard
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --capture=build/reports/vls/forest.png"
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --size=1100x720 --environment=1 --capture=build/reports/vls/snow-1100.png"
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --environment=2 --reduced-motion --capture=build/reports/vls/cave.png"
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --environment=3 --capture=build/reports/vls/lava.png"
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --reduced-motion --capture=build/reports/vls/glass-blur.png"
.\gradlew.bat runVlsDemo --args="--hidden --frames=30 --reduced-motion --no-blur --capture=build/reports/vls/glass-sharp.png"
```

`runVlsIntegration` 运行 100 帧真实 GL，经 `WindowInputCollector → UiSystem → hit test / focus → Button` 验证锁定、追踪、完成、解锁、行囊、环境切换、减少动效及 Tab／Enter。每帧核对柔焦开关确实控制公共 compositor 的两遍滤波，且没有预算降级。第 80 帧实际缩放窗口至 1100 × 720，检查面板不与底栏相碰，再验证缩放后的点击和键盘操作，并检查最终 framebuffer 的内容多样性。截图在 `build/reports/vls/`。NVIDIA 截图读回提示 131154 只对该验证任务登记单次例外，其他 GL 错误仍由现有门禁拦截。可通过 `./verify.ps1 -Mode UiConsumers -TimeoutSeconds 300` 在统一限时内运行 UI 定向检查及 VLS、Modern UI 短回放。

自动检查及演示窗口操作不等同于 Minecraft 内的实机玩法验收；本 Demo 不涉及多人或存档重启验证。
