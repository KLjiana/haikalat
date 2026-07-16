# Windows IME 适配与验收

v0.11 的 `Win32TextInputAdapter` 通过 GLFW 的 Win32 native handle 安装可链式 WndProc hook，
使用 JNA/JNA Platform 5.18.1 访问 User32、Kernel32 和 IMM32。普通 Unicode commit 与
composition/preedit 是两条明确分开的路径：

- 已提交字符只从 GLFW char callback 进入 `WindowInputSnapshot.committedCodePoints()`；
- WndProc 只发布 composition start/update/end；
- `GCS_RESULTSTR` 不读取、不调用 `TextInputClient.compositionCommitted()`，避免同一字符提交两次；
- 窗口线程把事件写入 `QueuedTextInputClient`，UI/update 线程再排空队列，native callback 不直接访问 UI tree。

## 生命周期

适配器必须在创建 GLFW 窗口的同一线程构造、激活、定位和关闭，并且必须先于窗口关闭：

```java
QueuedTextInputClient input = new QueuedTextInputClient();
try (GlfwWindow window = /* 创建 NORMAL cursor 的交互窗口 */;
     Win32TextInputAdapter ime = new Win32TextInputAdapter(window.handle())) {
    ime.activate(input);
    ime.setCandidateRect(caretRect);
    // pollEvents 后由 UI/update 线程 drain input。
    ime.deactivate(input);
}
```

`close()` 先停用 client、清除 preedit，再恢复原 WndProc。恢复失败不会释放 callback 强引用，
调用方应先移除后安装的 WndProc hook，再重试 `close()`；不能在 adapter 仍挂接时销毁 GLFW 窗口。
安装失败应降级到 `UnavailableTextInputAdapter`，GLFW committed-char 输入仍然可用。

候选矩形使用 Win32 client 坐标。UI paint 阶段应先完成逻辑坐标、content scale、viewport 和
scroll offset 转换，再调用 `setCandidateRect()`；适配器统一对左/上 floor、右/下 ceil，
同时更新 composition point 和 candidate exclude rectangle。

## 自动 smoke

纯 JVM 测试覆盖 hook 生命周期、线程所有权、preedit/caret/attribute、候选矩形取整、
latest command FIFO，以及 native result 不重复提交。非 Windows 测试会初始化公开 adapter 类，
确认不会尝试加载 Win32 DLL。

Windows 本机 WndProc smoke 与其他真实窗口测试共用开关：

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" `
  --tests "com.kaleblangley.haikalat.subsystems.windowing.input.win32.Win32TextInputAdapterSmokeTest"
```

该 smoke 创建隐藏 GLFW 窗口、安装 hook、同步发送 start/end 消息、验证 FIFO 事件，然后恢复
WndProc 并再次发送消息证明 callback 已断开。它不自动弹出或操纵用户输入法。

发布门槛额外提供两项不可缓存的压力任务：

```powershell
.\gradlew.bat runUiSyntheticImeSoak
.\gradlew.bat runUiNativeSoak
```

`runUiSyntheticImeSoak` 默认执行 100 轮 composition、失焦、resize/content-scale、活动
TextField 删除和 popup TextField 生命周期；可用 `-PuiImeSoakCycles=<次数>` 增加轮数。
`runUiNativeSoak` 默认执行 50 轮真实隐藏窗口、WndProc 安装、GC 压力、poll、hook 恢复和窗口
销毁；低于 50 轮会直接失败，可用 `-PuiNativeSoakRounds=<次数>` 增加轮数。

## 发布前手工矩阵

先启动 60 秒可交互窗口；可将时长设为 60～300 秒：

```powershell
.\gradlew.bat runUiInteractiveSoak -PuiSoakSeconds=60
```

以下矩阵已于 2026-07-17 使用 Microsoft Pinyin 完成人工验收。Emoji 可以作为 committed text
进入控件，但内建 Latin/CJK 字体不包含对应字形，v0.11 也不支持彩色 Emoji；这属于已知的
字体覆盖限制，不是 IME commit 丢失。

| 项目 | 状态 | 环境/证据 |
| --- | --- | --- |
| 拼音多次 preedit 更新、候选翻页、选择、commit 与 Escape cancel | 通过 | 2026-07-17 人工验收 |
| 中英文切换、Latin/CJK 混排 selection 替换、emoji 与组合字符 | 通过；Emoji 缺少可显示字形 | committed text 正常，渲染限制见上文 |
| ScrollView 与 Popup 内 TextField 的 caret/候选框跟随 | 通过 | 2026-07-17 人工验收 |
| resize 与 100%/125%/150%/200% DPI | 通过 | 2026-07-17 人工验收 |
| 窗口跨多显示器移动 | 通过 | 2026-07-17 人工验收 |
| Alt+Tab 失焦/恢复、焦点切换与删除 active TextField | 通过 | 2026-07-17 人工验收 |
| 关闭窗口时 adapter 先恢复 hook，无 callback use-after-free | 通过 | 2026-07-17 人工验收 |
| 每次 committed character 只出现一次，无 GLFW/`GCS_RESULTSTR` 重复 | 通过 | 2026-07-17 人工验收 |
