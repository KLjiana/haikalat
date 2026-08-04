# glTF 动画状态机桥接

`GltfSceneInstance` 支持两种互斥播放方式：

- 简单资源继续使用 `play(...)`、`playCombined(...)` 和 `update(...)`。
- 角色与战斗动画通过 `attachAnimationGraph(...)` 接入 `AnimationController`。

## 直接动画平滑过渡

不需要完整状态机时，`transitionTo(...)` 会在代码中从当前可见姿势生成过渡，混合所有骨骼的
translation、rotation 与 scale。默认过渡为 `0.18` 秒 ease-in-out，也可以显式传入时长：

```java
instance.play(idleIndex, AnimationPlayer.LoopMode.LOOP);
instance.transitionTo("move", AnimationPlayer.LoopMode.LOOP);        // 默认 0.18s
instance.transitionTo(attackIndex, AnimationPlayer.LoopMode.ONCE, 0.12f);
```

过渡过程中再次调用 `transitionTo(...)` 会从当时已经混合出的屏幕姿势继续，不会跳回前一个
clip。由 Graph Controller 切回直接播放时也可以使用同一接口平滑衔接。`play(...)` 仍保留
立即切换语义，供需要精确瞬切的调用使用。

## 接入状态机

Graph 必须使用实例暴露的同一个 skeleton 和 clip：

```java
AnimationGraph graph = AnimationGraph.builder("fighter", instance.animationSkeleton())
        .booleanParameter("grounded", true)
        .floatParameter("speed", 0.0f)
        .triggerParameter("attack")
        .state("idle", new ClipMotion(instance.animationClip(0)),
                AnimationPlayer.LoopMode.LOOP)
        .state("attack", new ClipMotion(instance.animationClip(1)),
                AnimationPlayer.LoopMode.ONCE)
        .entry("idle")
        .transition("idle", "attack", AnimationGraph.TransitionSpec.builder()
                .duration(0.12f)
                .destinationOffset(0.0f)
                .interruption(AnimationGraph.InterruptionPolicy.ANY)
                .when(AnimationGraph.Condition.trigger("attack"))
                .build())
        .build();

instance.attachAnimationGraph(graph);
```

游戏逻辑只依赖实例上的统一入口：

```java
instance.setBoolean("grounded", true);
instance.setFloat("speed", 2.5f);
instance.fireTrigger("attack");
instance.update(deltaSeconds);

for (AnimationSignal event : instance.drainEvents()) {
    // hit_start / hit_end / cancel_open / combo_open ...
}

List<String> windows = instance.activeAnimationWindows();
```

过渡的混合时长、目标起始偏移、打断策略和排队行为继续由
`AnimationGraph.TransitionSpec` 定义。直接调用 `play(...)` 会退出当前 Graph Controller，
恢复简单播放模式。

## Blender Marker

glTF animation 的 `extras` 可以携带 marker：

```json
{
  "name": "attack",
  "extras": {
    "fps": 30,
    "markers": [
      { "name": "hit_start", "frame": 8, "priority": "HIGH" },
      { "name": "hit_end", "frame": 11 },
      { "name": "cancel_open", "normalizedTime": 0.7 },
      { "name": "combo_open", "timeSeconds": 0.55 }
    ]
  }
}
```

时间字段可以使用 `timeSeconds`、`time`、`normalizedTime` 或 `frame`。`frame` 使用同一
`extras` 中的 `fps`，未声明时按 30 FPS 处理。

## 旁车 JSON

当 DCC 导出器不保留 extras 时，可在模型旁放置同名文件：

```text
fighter.glb
fighter.animation.json
```

```json
{
  "animations": {
    "attack": {
      "markers": [
        { "name": "hit_start", "timeSeconds": 0.25, "priority": "HIGH" },
        { "name": "hit_end", "timeSeconds": 0.42 },
        { "name": "cancel_open", "timeSeconds": 0.48 },
        { "name": "cancel_close", "timeSeconds": 0.7 },
        { "name": "combo_open", "timeSeconds": 0.5 },
        { "name": "combo_close", "timeSeconds": 0.65 }
      ]
    }
  }
}
```

使用 `GltfAssetLoader.loadWithSidecar(...)` 会依次查找 `.animation.json` 和
`.markers.json`。Marker 会转换成 `AnimationSignal.Type.MARKER`，由
`GltfSceneInstance.drainEvents()` 返回。

以 `_start`/`_end` 或 `_open`/`_close` 结尾的 Marker 还会同步维护
`activeAnimationWindows()`，用于直接查询 `hit`、`cancel`、`combo` 等当前窗口。
glTF Demo 的 `F2` 检查面板会显示状态、时间、归一化相位、活动窗口、混合权重、
目标状态和最近一次过渡原因。

当前 Graph morph 输出仅支持模型中恰好一个 morph 节点；多 morph 节点仍使用现有
`play(...)` 路径。
