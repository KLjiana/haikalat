# AsyncDemo 渲染线程契约

`AsyncDemo` 用于验证引擎的双线程边界，不是通用任务调度器。当前设计只保留平台/生产线程和 OpenGL 渲染线程；在出现独立物理、流式加载或后台解码需求前，不增加第三个常驻线程。

## 线程职责

平台/生产线程负责：

- 创建 GLFW 窗口、轮询或等待事件、读取输入；
- 更新相机和 CPU 侧实例矩阵；
- 创建不可变帧状态并向渲染线程提交上传请求；
- 请求停止，但不执行任何 OpenGL 调用。

OpenGL 渲染线程负责：

- 绑定唯一窗口 context、创建 capabilities 和全部 GL 资源；
- flush 已接受的上传请求；
- 记录并提交命令、绘制、交换前后缓冲；
- 在仍持有 context 时关闭所有 GL 资源，最后释放 context。

同一窗口的 context 不得同时绑定到两个线程。GL 资源创建、更新、绘制和销毁都必须发生在渲染线程。

## 帧状态与 GPU 数据一致性

生产线程可以快于渲染线程，因此帧状态采用 latest-wins 语义：中间帧允许被跳过，但已发布帧不能撕裂、倒退或引用仍在修改的矩阵。

`FrameState` 在构造时复制矩阵，`LatestFrameMailbox` 通过原子快照发布序号和值。实例矩阵先作为同一请求上传到实际参与绘制的 UBO；只有上传成功后，其回调才发布对应的 `FrameState`。因此渲染线程看到序号 N 时，GPU 缓冲与 CPU 元数据都属于 N。上传失败或请求被丢弃时，不发布该状态。

生产循环使用约 120 Hz 的事件等待进行节流。它不是固定时间步模拟，只是避免 VSync 开启时生产线程无上限占用 CPU。

## 生命周期与关闭顺序

`GlRenderThread` 的状态流为：

`NEW -> STARTING -> RUNNING -> STOPPING -> TERMINATED`

初始化或帧执行失败时进入 `FAILED`。启动完成信号必须在初始化成功或失败时都释放，避免调用方永久等待。

正常关闭顺序：

1. 停止接受新上传；
2. 让已接受的上传完成最后一次 flush；
3. 关闭生产侧 `FrameDriver`；
4. 在 GL 线程关闭 UBO、mesh、shader 等资源；
5. 释放窗口 context；
6. 完成渲染线程 future；
7. 平台线程销毁窗口。

失败关闭会清空未执行上传，仍尝试完成资源清理和 context 释放，并把清理异常附加到原始失败。外部 `close()` 必须等待线程终止；超时应抛出错误，不能静默返回。

## 验收入口

- `LatestFrameMailboxTest`：latest-wins、跨线程可见性和序号单调性；
- `UploadSystemTest`：上传成功后发布、失败/丢弃不发布、seal 后只排空已接受工作；
- `GlContextSmokeTest.asyncUploadBufferDrivesInstancedDrawPixels`：真实 UBO 上传驱动实例绘制并产生非空像素；
- `./gradlew.bat runAsyncIntegration`：隐藏窗口运行有限帧并完成线程与资源关闭；
- `./gradlew.bat localGlVerification`：运行全部本地 GL 验收。

