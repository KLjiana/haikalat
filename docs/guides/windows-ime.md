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

## 发布前手工矩阵

在 Microsoft Pinyin 下记录下列项目，自动 smoke 不能替代这些检查：

1. 拼音多次 preedit 更新、翻页、候选选择、commit 和 Escape cancel；
2. 中英文切换、Latin/CJK 混排 selection 替换、emoji 与组合字符；
3. TextField 位于 ScrollView 和 Popup 内时，caret 移动后候选框跟随；
4. 窗口 resize、100%/125%/150%/200% DPI、多显示器移动；
5. Alt+Tab 失焦/恢复、焦点切换和删除 active TextField；
6. 关闭窗口时先关闭 adapter，无 WndProc callback use-after-free；
7. 每次 commit 只出现一次，确认 GLFW char 与 `GCS_RESULTSTR` 没有重复写入。
