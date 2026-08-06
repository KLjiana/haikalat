# 序列化场景 v1

v0.19 的正式场景格式是严格 UTF-8 JSON，根字段固定为：

```json
{
  "format": "haikalat.scene",
  "version": 1,
  "camera": {
    "node": "camera-main",
    "projection": {
      "type": "perspective",
      "fovYDegrees": 60.0,
      "near": 0.1,
      "far": 500.0
    }
  },
  "nodes": [
    {"id": "world"},
    {
      "id": "hero",
      "parent": "world",
      "renderable": {
        "type": "gltf",
        "asset": "demo:scenes/gltf/zombie.gltf",
        "animated": true,
        "initialAnimation": "run",
        "loop": true,
        "castShadows": false
      }
    }
  ]
}
```

资源引用使用 `namespace:path` 或场景相对的 `./...` / `../...`。无 namespace 的裸路径、
绝对路径、远程 URI、query、fragment 和跨根相对路径都会被拒绝。

解析入口是：

```java
SceneDefinition definition = SceneJsonParser.parse(sceneId, utf8Bytes);
```

解析结果是不可变 CPU 数据，不包含 OpenGL 对象。未知字段、重复字段、非法浮点数、
parent 环、未知相机节点、灯光上限和 glTF 引用错误都会在上传前失败。

v1 只支持 perspective 相机、directional/point/spot 灯光和 glTF/GLB renderable。
Prefab、ECS、通用 component map 和状态迁移不属于这个版本。

## v0.21 角色 descriptor

v0.21 在原有 `camera`/`nodes` 结构上增加可选的 `characters` 数组。没有 `characters`
的旧 scene 保持原行为。

```json
{
  "format": "haikalat.scene",
  "version": 1,
  "camera": {"node": "camera", "projection":
    {"type": "perspective", "fovYDegrees": 60, "near": 0.1, "far": 100}},
  "nodes": [{"id": "camera"}, {"id": "player", "renderable":
    {"type": "gltf", "asset": "./player/animation-library.json"}}],
  "characters": [{"id": "player-character", "object": "player",
    "animationLibrary": "./player/animation-library.json",
    "animationGraph": "./player/player.animation-graph.json",
    "initialState": "idle", "parameters": {"grounded": true, "speed": 0.0}}]
}
```

`SceneJsonParser` 会在 CPU 阶段拒绝未知字段、重复 character/object、缺失 glTF
renderable、路径穿越和超出数量限制的参数。`SceneAssetService` 会将 model、library
和 Graph sidecar 一起发布到 scene 的 generation dependency set；任意依赖失效都会
让 scene candidate 重新构建，而不会修改当前 active generation。

当前主路径示例：

```powershell
.\gradlew.bat runSerializedSceneIntegration
```

`SerializedSceneDemo` 使用同一个 `SceneBuildPlan` 上传 player_wild，普通窗口和
embedded host 可以共享该 CPU plan。原始 GeckoLib Molang、Minecraft 事件、声音和粒子
仍不属于 Haikalat 主仓库的解析承诺。
