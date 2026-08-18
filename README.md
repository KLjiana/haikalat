# haikalat

[English](README.md) | [简体中文](README.zh-CN.md)

Java/LWJGL OpenGL renderer with a small layered rendering framework:
`backend`, `core`, `subsystems`, and `runtime`.
Dependencies flow from `subsystems/runtime` to `core` to `backend`; lower layers
do not import higher layers.

See `docs/planning/project-goals.md` for the
current goals, capabilities, and non-goals.

## Current Capabilities

- OpenGL backend resources for stage-aware graphics/compute shaders, buffers, textures, samplers, framebuffers,
  vertex layouts/arrays, render formats, uniform blocks, GPU fences/timers, state caching,
  and error reporting.
- Core rendering protocols for command recording, render devices, render graphs,
  mesh data, instancing, upload flow, immutable frame snapshots, and materials.
- GL-free scalar curves, cubic-bezier easing, Hermite property tracks, HDR color gradients,
  fixed-table cubic Bezier paths, and curve lookup tables shared by UI, animation, and VFX.
- A forward 3D scene pipeline with basic Blinn-Phong lighting, fixed-size 1-4 cascade
  directional shadows for ordinary and opt-in instanced casters, 3x3 PCF,
  linear HDR/ACES tone mapping, optional multi-level Bloom, explicit linear/sRGB textures,
  selectable none/MSAA/FXAA/TAA paths, finite/unbounded mesh bounds, stable camera/shadow
  frustum culling, explicit MSAA color/depth resolve, and OPAQUE/MASKED/ALPHA/ADDITIVE
  queues with stable camera-space transparent sorting.
- Asset helpers for classpath resources, shader assets, texture caching,
  `.properties` scene configuration, the OBJ path exercised by the main demo,
  and glTF 2.0 static/skinned assets plus manifest/ZIP external JSON animation
  libraries exercised by the dedicated glTF demo.
- A deterministic, GL-free animation runtime with reusable pose/morph scratch, reverse playback,
  typed Graph states/transitions/triggers, 1D and explicit-triangle 2D Blend Trees, up to eight
  Override/Additive layers, masks, marker synchronization, bounded signals, root motion,
  Look-at/JointLimit/FABRIK/two-hand/pure-input foot constraints, and a one-way VFX bridge.
  glTF skins plus POSITION/NORMAL/TANGENT Morph Targets and weights animation are evaluated per
  instance; Morph runs before four-weight skinning in both PBR forward and directional shadow.
- A strict OpenGL 4.6 production capability contract covering Core Profile, DSA, SSBO,
  compute, image load/store, buffer storage, MDI, shader draw parameters, and debug output.
  Optional bindless-texture support is reported separately; there is no legacy fallback.
- A GL-free resource subsystem with normalized `AssetId` values, bounded directory/classpath
  sources, per-resource generations, and asynchronous CPU decoding that rejects stale results.
- Strict `haikalat.scene` v1 JSON loading with explicit namespace catalogs, background
  scene/glTF/image decoding, staged GL upload budgets, exact-generation GPU leases,
  transactional scene replacement, manual reload, and optional debounced directory watching.
- A retained-mode game UI subsystem with Yoga layout, typed theme/widgets/events,
  virtualized lists, popup/focus/clipboard support, bundled Noto Sans SC shaping through
  FreeType/HarfBuzz, a GPU glyph atlas, typed scissor/texture uploads, Windows IME composition,
  and GL-free visual/layout Tween and Transition channels.
- Post-processing color grading through tiled 2D LUTs and depth-reconstructed distance/height fog,
  with deterministic pixel and resize verification.
- A GL-free deterministic VFX subsystem with bounded particle, Ribbon and Decal simulation,
  configurable size/color/rotation/width/scale over-life curves,
  pure-value textured materials and stable transparent sorting. Its render3d adapter supports
  R8/sRGB masks, alpha/additive emissive rendering, billboard/stretch modes, scene-depth soft
  particles, and pre-tone-map HDR/Bloom composition while keeping UI outside Bloom.
- Controlled OpenGL 4.6 experiments for compute/SSBO GPU particles without CPU readback and
  bounded screen-space volumetric spot lighting, with real-pixel and resource-stability checks.
- Directional, six-face point-atlas and spot shadow passes, with an operational 2-4 tile,
  practical-split, world-texel-stabilized directional cascade atlas.
- Demo proof paths for an empty present baseline, the combined scene pipeline, minimal command/window flow,
  CPU skeletal animation, async update/upload/render-thread interaction, a dedicated UiDemo,
  a Milestone 4 animation/PBR/postprocess/VFX/UI showcase, a dedicated v0.23 Render3D
  queue/MASK/Fog+MSAA/CSM/diagnostics scene, and generated GPU-procedural 1000000-instance
  stress profiling.
- Bounded runtime diagnostics with frame/pass sample identity, RenderGraph inspection, structured GL messages,
  tracked resources, scene visibility/queue statistics, an F2 retained UI panel, frozen history,
  and deterministic schema-v1 JSON capture.

## Requirements

- JDK 21 or newer.
- A GPU/driver with OpenGL 4.6 Core Profile support.
- Windows or Linux with an OpenGL 4.6 driver. LWJGL natives are selected automatically from the current OS and CPU architecture.
- A desktop session is required for demos because they create GLFW windows.

## Build And Test

Default tests are pure JVM/unit checks and do not require a desktop GL context:

```powershell
.\gradlew.bat compileJava test
```

CI runs the same non-windowed path plus demo source compilation:

```powershell
.\gradlew.bat compileJava demoClasses test
```

The equivalent named no-desktop verification gate is:

```powershell
.\gradlew.bat quickVerification
```

Opt-in GL smoke checks create a hidden GLFW window and verify minimal GL/resource lifecycle behavior:

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

See `docs/guides/testing.md` for the `unit`, `glSmoke`, and `integration` categories.

Run the focused serialized-scene JVM and real-GL gate:

```powershell
.\gradlew.bat localSceneAssetVerification --rerun-tasks
```

Run the hidden deterministic baseline scene for eight frames:

```powershell
.\gradlew.bat runDemoIntegration
```

Run the interactive Render3D v0.23 proof scene, or its hidden finite contract check:

```powershell
.\gradlew.bat runRender3dV023Demo
.\gradlew.bat runRender3dV023Integration
```

Run deterministic resize/async integrations or the complete local GL verification:

```powershell
.\gradlew.bat runDemoResizeIntegration
.\gradlew.bat runAnimationIntegration
.\gradlew.bat runAnimationGraphIntegration
.\gradlew.bat runAnimationConstraintIntegration
.\gradlew.bat runGltfMorphSkinningIntegration
.\gradlew.bat localAnimationVerification
.\gradlew.bat runGltfSkinningIntegration
.\gradlew.bat runPostProcessEffectsIntegration
.\gradlew.bat runVfxIntegration
.\gradlew.bat runVfxCurveBenchmark
.\gradlew.bat runShowcaseIntegration
.\gradlew.bat runShowcaseStabilityIntegration
.\gradlew.bat runLocalShadowsIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat localUiVerification
.\gradlew.bat localGlVerification
.\gradlew.bat localDiagnosticsVerification
.\gradlew.bat localSceneVisibilityVerification
```

Open the main Demo diagnostics panel with F2, or create a deterministic capture with:

```powershell
.\gradlew.bat runDiagnosticsIntegration
.\gradlew.bat runDiagnosticsBenchmarks
.\gradlew.bat runSceneScalabilityBenchmarks
```

Run the complete local release gate only on a desktop system with a supported
OpenGL driver:

```powershell
.\gradlew.bat localReleaseVerification
```

Run the interactive Bloom scene or the formal five-round post-v0.8 benchmark suite:

```powershell
.\gradlew.bat runBloomDemo
.\gradlew.bat runPostV08Benchmarks
```

The documentation index is available at [`docs/README.md`](docs/README.md).

New contributors can start with the [Chinese newcomer guide](docs/guides/newcomer-guide.md), which walks through the window loop, core rendering APIs, and runnable code examples.

## Run Demos

Import the Gradle project in your IDE, then run the demo `main` method directly from
the `learnopengl.demo` module.

- Main demo: `com.kaleblangley.haikalat.demo.LearnOpenGlDemo`
- Empty no-draw window: `com.kaleblangley.haikalat.demo.EmptyWindowDemo`
- Minimal smoke demo: `com.kaleblangley.haikalat.demo.MinimalDemo`
- CPU skeletal animation: `com.kaleblangley.haikalat.demo.animation.AnimationDemo`
- Static and skinned glTF/PBR: `com.kaleblangley.haikalat.demo.gltf.GltfDemo`
- PBR, color grading and fog: `com.kaleblangley.haikalat.demo.pbr.PbrDemo`
- Particle, Ribbon and Decal VFX: `com.kaleblangley.haikalat.demo.vfx.VfxDemo`
- Combined animation/PBR/postprocess/CPU+GPU VFX/UI showcase: `com.kaleblangley.haikalat.demo.pbr.HaikalatShowcaseDemo`
- Async/upload demo: `com.kaleblangley.haikalat.demo.async.AsyncDemo`
- Retained UI demo: `com.kaleblangley.haikalat.demo.ui.UiDemo`
- Markdown block parsing and text effects: `com.kaleblangley.haikalat.demo.ui.MarkdownTextDemoMain`
- Ordinary-renderer visibility benchmark: `com.kaleblangley.haikalat.demo.SceneScalabilityDemo`

The demo source set lives in `src/demo/java` and uses resources from `src/demo/resources`.

## Distribution And License

Running `./gradlew jar` produces both `haikalat-<version>.jar` and
`haikalat-<version>-sources.jar` in `build/libs`. Both archives include the project license at
`META-INF/LICENSE`.

Haikalat is licensed under the GNU Affero General Public License v3.0 only
([AGPL-3.0-only](LICENSE)). Bundled third-party assets and dependencies retain the licenses recorded
under [`docs/licenses`](docs/licenses).
