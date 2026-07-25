# 资源代次与异步解码合同

`subsystems.resources` 只处理宿主无关的资源身份、编码字节和 CPU 解码结果，不创建或更新
OpenGL 对象。

## 身份与来源

- `AssetId` 由规范化 namespace 和逻辑路径组成，不接受绝对路径、URI scheme、查询参数、
  fragment 或越过资源根的 `..`。
- `ResourceSource` 按 `AssetId` 读取有明确上限的编码字节。内建 directory/classpath source
  只是协议实现，不把宿主 SDK 或宿主资源类型带入引擎。
- glTF、纹理、着色器、VFX 和 UI 主题可以共享该边界，但各自保留具体格式的解码器。

## 代次与发布

1. 调度读取前，`ResourceGenerationTracker.capture` 产生 `(AssetId, generation)` 票据。
2. `AsyncResourceDecoder` 在调用方 executor 或自有虚拟线程中读取并解码，只产出 CPU 值。
3. reload/invalidate 递增该 `AssetId` 的 generation，所有旧票据立即过期。
4. CPU 结果通过 `publishIfCurrent` 发布；过期结果必须丢弃，不能覆盖新代次。
5. 若结果还需排队上传 GPU，上传请求必须携带原票据，并在渲染线程实际提交前再次调用
   `isCurrent`。OpenGL 资源创建、替换和销毁仍归 backend/渲染线程所有。

`publishIfCurrent` 在单资源代次锁内调用 publisher，以保证检查与发布之间不会插入 invalidate。
publisher 应保持短小且只做 CPU 状态交换或入队，不能执行阻塞 I/O 或直接调用 OpenGL。
