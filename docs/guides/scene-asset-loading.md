# 场景异步加载与热重载

先挂载逻辑 namespace，再创建场景服务：

```java
ResourceCatalog catalog = ResourceCatalog.builder()
        .mount("engine", ResourceSource.classpath("engine", EngineAssets.class))
        .mount("demo", ResourceSource.directory("demo", demoRoot))
        .build();

try (SceneAssetService service = new SceneAssetService(catalog)) {
    AssetId sceneId = AssetId.parse("demo:scenes/showcase.scene.json");
    SceneHandle handle = service.open(sceneId);
    CompletableFuture<SceneBuildPlan> ready = service.loadPlan(sceneId);
    // 后台完成 scene read/parse/validate、glTF 与图片 RGBA8 CPU decode。
    SceneBuildPlan plan = ready.join();
}
```

运行时应让 GL 线程在每帧开始推进上传和发布，不要在渲染线程等待完整解码：

```java
GltfGpuAssetCache cache = new GltfGpuAssetCache(runtimeLibrary);
SceneHandle handle = service.open(sceneId);

// frame start, GL thread:
service.pumpUploads(cache, SceneUploadBudget.frameDefault());
service.applyReadyScenes(candidate -> {
    pipeline.replaceScene(candidate.scene());
    return true;
});
```

`SceneUploadBudget` 同时限制每帧的资源步骤数、软时间预算和估算上传字节数。单个
OpenGL 资源创建不能中途抢占；若某个纹理或网格本身大于字节预算，会单独占用一次 pump，
而不会永久饿死在队列中。

同一个 `(scene AssetId, generation)` 的并发 `loadPlan` 会共享一个 future。修改或手动失效时
只推进 generation，旧 future、旧上传候选不会覆盖新版本：

```java
service.reload(sceneId);
Set<AssetId> affected = service.invalidate(changedGltf);
```

`SceneBuildPlan` 是 GL-free 的不可变交接值。若应用需要手动控制单个候选，也可以在 GL
线程使用显式的 prepare/activate/commit：

```java
GltfGpuAssetCache cache = new GltfGpuAssetCache(runtimeLibrary);
service.applyReadyScene(handle, plan, cache, candidate -> {
    pipeline.replaceScene(candidate.scene());
    return true;
});
```

候选版本只有在所有 glTF GPU asset、instance、camera 和 lights 创建完成后才会交给
activator。activator 拒绝或抛错时旧 `SceneHandle` 继续有效。成功提交后旧版本按
instance → lease → GPU asset 的顺序退休。

开发期可以打开 directory watcher：

```java
service.watchDirectory("demo", demoRoot, Duration.ofMillis(120));
```

watcher 只适用于 directory source；classpath/JAR 不声明可监控。事件只是失效提示，内部会
去抖、合并并把路径转换成 `AssetId`。手动 `reload` 始终可用。

出现加载失败时，旧的 `SceneHandle.current()` 保持不变；可以通过快照读取有限诊断信息：

```java
SceneAssetSnapshot diagnostics = service.snapshot();
diagnostics.handles();
diagnostics.pendingUploadRequests();
diagnostics.readyCandidates();
diagnostics.recentFailures(); // 最多保留最近 16 条结构化失败
diagnostics.lastFailurePhase();
diagnostics.lastFailureAsset();
```
