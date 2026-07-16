# learnopengl

[English](README.md) | [简体中文](README.zh-CN.md)

Java/LWJGL OpenGL learning renderer with a small layered rendering framework:
`backend`, `core`, `subsystems`, and `runtime`.
Dependencies flow from `subsystems/runtime` to `core` to `backend`; lower layers
do not import higher layers.

The project goal is to keep a learning-oriented but engineering-constrained
OpenGL renderer: stable enough to validate rendering architecture, small enough
to avoid becoming a full game engine. See `docs/planning/project-goals.md` for the
current goals, capabilities, and non-goals.

## Current Capabilities

- OpenGL backend resources for stage-aware graphics/compute shaders, buffers, textures, samplers, framebuffers,
  vertex layouts/arrays, render formats, uniform blocks, GPU fences/timers, state caching,
  and error reporting.
- Core rendering protocols for command recording, render devices, render graphs,
  mesh data, instancing, upload flow, immutable frame snapshots, and materials.
- A forward 3D scene pipeline with basic Blinn-Phong lighting, fixed-size
  directional shadows for ordinary and opt-in instanced casters, 3x3 PCF,
  linear HDR/ACES tone mapping, optional multi-level Bloom, explicit linear/sRGB textures,
  and selectable none/MSAA/FXAA/TAA paths.
- Asset helpers for classpath resources, shader assets, texture caching,
  `.properties` scene configuration, and an OBJ path exercised by the main demo;
  the Assimp loader remains experimental.
- A retained-mode game UI subsystem with Yoga layout, typed theme/widgets/events,
  virtualized lists, popup/focus/clipboard support, bundled Noto Sans SC shaping through
  FreeType/HarfBuzz, a GPU glyph atlas, typed scissor/texture uploads, and Windows IME composition.
- Demo proof paths for an empty present baseline, the combined scene pipeline, minimal command/window flow,
  async update/upload/render-thread interaction, a dedicated UiDemo, and generated GPU-procedural
  1000000-instance stress profiling.

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

Opt-in GL smoke checks create a hidden GLFW window and verify minimal GL/resource lifecycle behavior:

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

See `docs/guides/testing.md` for the `unit`, `glSmoke`, and `integration` categories.

Run the hidden deterministic baseline scene for eight frames:

```powershell
.\gradlew.bat runDemoIntegration
```

Run deterministic resize/async integrations or the complete local GL verification:

```powershell
.\gradlew.bat runDemoResizeIntegration
.\gradlew.bat runAsyncIntegration
.\gradlew.bat localUiVerification
.\gradlew.bat localGlVerification
```

Run the interactive Bloom scene or the formal five-round post-v0.8 benchmark suite:

```powershell
.\gradlew.bat runBloomDemo
.\gradlew.bat runPostV08Benchmarks
```

The documentation index is available at [`docs/README.md`](docs/README.md).

## Run Demos

Import the Gradle project in your IDE, then run the demo `main` method directly from
the `learnopengl.demo` module.

- Main demo: `com.kaleblangley.haikalat.demo.LearnOpenGlDemo`
- Empty no-draw window: `com.kaleblangley.haikalat.demo.EmptyWindowDemo`
- Minimal smoke demo: `com.kaleblangley.haikalat.demo.MinimalDemo`
- Async/upload demo: `com.kaleblangley.haikalat.demo.async.AsyncDemo`
- Retained UI demo: `com.kaleblangley.haikalat.demo.ui.UiDemo`

The demo source set lives in `src/demo/java` and uses resources from `src/demo/resources`.
