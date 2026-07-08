# learnopengl

Java/LWJGL OpenGL learning renderer with a small layered rendering framework:
`backend`, `core`, `subsystems`, and `runtime`.

The project goal is to keep a learning-oriented but engineering-constrained
OpenGL renderer: stable enough to validate rendering architecture, small enough
to avoid becoming a full game engine. See `docs/project-goals.md` for the
current goals, capabilities, and non-goals.

## Current Capabilities

- OpenGL backend resources for shaders, buffers, textures, samplers, framebuffers,
  vertex arrays, uniform blocks, GPU fences, state caching, and error reporting.
- Core rendering protocols for command recording, render devices, render graphs,
  mesh layouts, instancing, upload flow, triple buffering, and materials.
- A forward 3D scene pipeline with basic lights, directional shadow-map pass,
  and selectable none/MSAA/FXAA/TAA postprocess paths.
- Asset helpers for classpath resources, shader assets, texture caching,
  `.properties` scene configuration, and OBJ/Assimp model loading entry points.
- Demo proof paths for the combined scene pipeline, minimal command/window flow,
  and async update/upload/render-thread interaction.

## Requirements

- JDK 21 or newer.
- A GPU/driver with OpenGL 3.3 Core Profile support.
- Windows, Linux, or macOS. LWJGL natives are selected automatically from the current OS and CPU architecture.
- A desktop session is required for demos because they create GLFW windows.

## Build And Test

```powershell
.\gradlew.bat compileJava test
```

CI runs the non-windowed checks:

```powershell
.\gradlew.bat compileJava demoClasses test
```

## Run Demos

Import the Gradle project in your IDE, then run the demo `main` method directly from
the `learnopengl.demo` module, for example:

- `com.kaleblangley.haikalat.demo.MinimalDemo`
- `com.kaleblangley.haikalat.demo.LearnOpenGlDemo`
- `com.kaleblangley.haikalat.demo.async.AsyncDemo`

The demo source set lives in `src/demo/java` and uses resources from `src/demo/resources`.
Unit tests live in `src/test/java` and should avoid requiring a real OpenGL context unless explicitly marked otherwise.
