# player_slie glTF 渲染

`player_slie.gltf` 是自包含的 Blockbench 导出资源，内含 PNG 贴图、蒙皮关节和两个动画：

- `animation`：静态姿势通道；
- `animation2`：包含可见下肢运动的 0～0.25 秒动作。

Demo 播放 `animation2` 并以 0.75 倍速运行。由于材质使用 `MASK` alpha，当前实例关闭投影
阴影，但仍参与完整的 PBR 前向渲染。

资源位置：

```text
src/demo/resources/scenes/gltf/player_slie.gltf
```

运行交互演示：

```powershell
.\gradlew.bat runPlayerSlieDemo
.\gradlew.bat runGltfDemo --args="--asset=player_slie"
```

运行隐藏窗口的真实 OpenGL 验证：

```powershell
.\gradlew.bat runPlayerSlieIntegration
```
