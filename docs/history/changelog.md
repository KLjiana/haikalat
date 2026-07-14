# 变更记录

## Unreleased

### Stabilization

- Replaced opaque per-command `Consumer<StateCache>` captures with a reusable typed opcode stream and recording-time pending pipeline state. Final blend/depth/cull/viewport/clear-color values are emitted only at observable boundaries, while draw/pass/transparency ordering and cross-frame `StateCache` dedup remain intact.
- Added formal RenderGraph GPU timer query commands, made `custom()` a flush+invalidate full barrier, and added unit/real-GL regressions for state collapse, depth-mask-controlled clear, and opaque/transparent ordering across draws and passes.
- Added formal indexed-instanced command recording and a no-VBO procedural topology path: Cube uses 8 bit-decoded corners/36 cache-ordered uint8 indices, Quad uses 4/6, and back-face culling remains enabled.
- Added a 16-byte `std430 uvec4` packed instance codec, immutable one-upload SSBOs, offset-aligned persistent mapped dynamic rings with per-slot fences and dirty-range propagation, plus generated indexed/compact shaders and real-pixel GL coverage.
- Expanded StressDemo into `gpu`, `indexed`, `indexed-ssbo`, and `dynamic` A/B modes with average/median CPU/GPU timing and ARB vertex-shader invocation counters; normalized EmptyWindowDemo to the same 1280x720 clear/present benchmark conditions.
- Added `EmptyWindowDemo` and an Nsight launcher as a 1280x720 clear/present baseline; it intentionally issues no shader, VAO, buffer, texture, or draw commands and has a deterministic hidden integration.
- Completed the v0.7 stable-assets vertical slice: validated manifest references, packaged a self-authored OBJ baseline, loaded it through `ModelAssetManager`/`ObjModelLoader`, mapped model meshes into the scene, and centralized demo GL resource ownership.
- Added deterministic resize and async integrations, GL render-thread failure/timeout/cleanup coverage, stricter architecture/custom-command guards, and the aggregate `localGlVerification` task.
- Replaced the unsafe bare-reference CPU `TripleBuffer` with immutable latest-frame snapshots; Minimal now uses a plain same-thread list and `InstancedRenderer` publishes copied transform lists.
- Made AsyncDemo upload the instance matrices consumed by its shader UBO, publish matching immutable metadata only after upload success, pace its producer, and document the two-thread ownership/shutdown contract.
- Merged duplicate demo fragment shaders into shared lit and vertex-color variants, and centralized the 4x4 demo instance-grid constants/transforms.
- Kept upload merging scoped to the exact target object, including when two targets expose the same numeric buffer ID.
- Added the reusable runtime `FrameClock`; camera movement and AsyncDemo animation now use capped elapsed time instead of GLFW/frame-count timing, so input events cannot accelerate animation.
- Enforced `subsystems/runtime -> core -> backend`: moved OpenGL formats, upload targets, vertex layouts and GPU timers downward, moved graph profiles into core, and removed every backend-to-core/core-to-runtime import.
- Preserved `StateCache` across command buffers, added uniform-buffer and clear-color caching, limited direct instancing invalidation to vertex-input state, removed sampler bind round-trips, and exposed applied/avoided counters through `FrameDriver`.
- Unified runtime timing: `FrameDriver.present` measures completed swaps, `RenderStatistics` separates one-second present FPS from CPU submit/GPU time, async readers use immutable snapshots, and `PeriodicTimer` limits all demo title updates to 250 ms.
- Defined non-overlapping responsibilities for Minimal/LearnOpenGL/Async demos and added StressDemo with configurable triangle/quad/cube workloads, a reusable indexed cube, three 100000-instance integrations, Nsight launcher, and CPU/GPU/present/state-cache metrics.
- Added a GPU-driven StressDemo path with procedural `gl_VertexID` geometry and `gl_InstanceID` transforms/colors, DSA buffer/VAO creation, persistent coherent instance mapping, non-blocking GPU query rings, compact material state packets, and safe cached scene draw ordering.
- Generalized shaders to all OpenGL 4.6 core stages with DSA uniforms, UBO/SSBO/image bindings and compute dispatch commands; generalized shader assets/hot reload by stage and added a real compute-to-SSBO readback test.
- Replaced the hand-written multi-shape stress shader with a build-time generator sourced from `BuiltinMeshData`; generated shape-specialized flattened GLSL removes shape branches, integer division and repeated trigonometry, while RenderGraph reuses immediate command/timing storage.
- Fixed multi-directional-light shadow association with an explicit bounded shader light index, and added final-pixel GL assertions for lighting, shadowing, caster movement, and light-direction changes.
- Migrated Minimal/Async demo window, render-target, mesh, frame-driver, upload-queue, and cleanup paths onto the retained engine APIs; unified builtin-mesh instancing at attribute 3 and made GL render-thread completion wait for resource cleanup.
- Completed the real-capabilities closure roadmap: Java 21 toolchains, Windows/Linux CI, lit baseline shaders, bounded light input, fixed-size directional shadow targets, real caster draws, depth sampling with bias/3x3 PCF, deterministic demo integration, GL output checks, and package dependency guards.

- Converted front-line development feedback into prioritized stabilization tasks; completed task details were later consolidated into this changelog.
- Documented the project goal, current capabilities, target direction, and non-goals in `docs/planning/project-goals.md`.
- Audited abstraction density and retained the lasting rules in `docs/architecture/abstraction-density.md` and `docs/architecture/render-boundaries.md`.
- Removed over-broad resource factory and future-only descriptor abstractions that had no production behavior.
- Annotated all `cmd.custom()` call sites with replacement direction and whether a formal command API is needed.
- Added formal `CommandBuffer.drawInstancedBatch(...)` commands and removed instanced batch `cmd.custom()` usage from demo/runtime main paths.
- Removed redundant transform snapshotting from the instanced batch command path.
- Split `RenderPipeline` internals into package-private helpers for camera uniforms, lighting binding, TAA history, postprocess pass creation, and forward pass registration.
- Added narrow `PassResources` accessors for logical color attachments/current targets and documented backend-facing resource APIs.
- Introduced pure `MeshData` and builtin mesh data helpers, with `Mesh.from(MeshData)` as the GL upload boundary.
- Added non-GL `MaterialDef` scene configuration and documented `Material` as an OpenGL runtime resource.
- Removed legacy default-package demo entry points and their private shaders, keeping the packaged demo entry points as the supported style.
- Defined `.properties` scene config as asset manifest plus simple demo-scene bindings, with a fixed builtin mesh name set.
- Cleaned the demo scene manifest and kept it within the documented asset/simple-scene scope.
- Documented test categories for pure JVM unit tests, opt-in GL smoke tests, and manual/integration checks.
- Added opt-in GL smoke checks for framebuffer, shader, texture, and texture cache lifecycle boundaries.
- Defined the minimal `RenderDevice` contract and kept OpenGL `StateCache` access on `GlRenderDevice`.
- Documented `RenderGraph` pass/resource/profile boundaries in `docs/architecture/render-boundaries.md`.
- Added standard Java `.properties` scene asset config parsing and switched `LearnOpenGlDemo` to that format.
- Kept `LearnOpenGlDemo` as the combined scene, shadow, and postprocess proof demo.
- Added an opt-in hidden-window GL smoke test with pixel readback verification.
- Removed deprecated or unused thin abstractions: `UploadQueue`, `AntiAliasPipeline`, `DefaultAssetManagers`, and `DeferredPipelinePlan`.

### Completed Roadmap Work

The completed items previously listed in `docs/planning/future-plans.md` have been migrated here so the roadmap can focus on next work instead of historical checklists.

#### v0.1-stable-demo

- Trimmed LWJGL dependencies and selected natives by operating system.
- Split demo sources into `src/demo/java` and kept demo entry points runnable from the IDE.
- Documented runtime requirements in the README.
- Stabilized demo resize, zero-height projection handling, empty-scene clear/present, async initialization, and resource cleanup.

#### v0.2-tested-core

- Added tests for `VertexPacking`, `RenderGraph`, `UploadSystem`, and `TripleBuffer`.
- Added CI coverage for compilation and unit tests that do not require an OpenGL context.
- Tightened RenderGraph pass dependencies and command capture rules.
- Added a formal framebuffer blit command to reduce custom command usage.

#### v0.3-materials

- Split material template and material instance state.
- Added typed `UniformValue` handling, UBO support, sampler configuration, and clearer resource ownership rules.

#### v0.4-postprocess-graph

- Integrated FXAA and TAA as RenderGraph passes.
- Added postprocess mode selection, texture naming conventions, TAA jitter/history validation, and resize history reset.

#### v0.5-instancing-framebuffer

- Extended instanced batching for grouped mesh submission, synchronization, persistent mapping evaluation, statistics, and replaceable instance layouts.
- Added MRT, depth texture support, framebuffer descriptors, render target lifecycle management, and resize rebuild flow.

#### v0.6-engine-surface

- Introduced a minimal backend boundary with `RenderDevice` and resource barrier concepts.
- Added scene data structures, forward pipeline, basic lighting, deferred pipeline evaluation, and directional shadow map support.
- Added model loading, texture asset cache, shader hot reload, resource locator support, and configurable demo scene data.
- Added pass-level GPU timing, CPU/GPU frame profiles, debug overlay data, GL object labels, and render error categories.
