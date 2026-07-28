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
