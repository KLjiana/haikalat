# learnopengl

Java/LWJGL OpenGL learning renderer with a small layered rendering framework:
`backend`, `core`, `subsystems`, and `runtime`.

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
