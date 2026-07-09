# Testing

This project uses three test categories. The default Gradle `test` task must stay safe for CI machines without a desktop OpenGL environment.

## Unit

Pure JVM tests that do not create a GLFW window, initialize GL capabilities, compile real shaders, upload textures, or allocate real framebuffers.

Use this category for:

- data structures and descriptors
- command recording order
- asset/config parsing
- pure mesh data and model parsing
- RenderGraph topology and no-context behavior
- runtime statistics and error classification

Naming/package convention: ordinary `*Test` classes outside `com.kaleblangley.haikalat.integration`, with no special system property.

Default command:

```powershell
.\gradlew.bat test
```

## GL Smoke

Opt-in tests that create a hidden GLFW window and a minimal OpenGL context. These verify that the local graphics stack can execute basic GL work.

Use this category for:

- shader compile/link checks that need real GL
- framebuffer creation, resize, completeness, and lifecycle checks
- texture upload and sampler behavior
- asset cache behavior that depends on real `Texture2D` or `ShaderProgram`
- minimal pixel readback or clear validation

Naming/package convention: `*SmokeTest` under `com.kaleblangley.haikalat.integration`, guarded by:

```java
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
```

Command:

```powershell
.\gradlew.bat test "-Dhaikalat.glSmoke=true" --rerun-tasks
```

## Integration

Manual or environment-specific checks that exercise full demo or pipeline behavior. These may open visible windows, depend on GPU/driver behavior, or require screenshot/pixel comparison beyond a minimal smoke check.

Use this category for:

- demo startup and resize workflows
- end-to-end scene pipeline behavior
- shadow/postprocess visual validation
- longer-running frame loop checks

Naming/package convention: `*IntegrationTest` under `com.kaleblangley.haikalat.integration`, or manual demo entry points in `src/demo/java`. Integration tests must remain opt-in through a system property or a separate Gradle task if they are automated later.

CI should run only the default unit path unless the environment explicitly provides a desktop GL context.
