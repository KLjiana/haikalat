package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.AntiAliasingMode;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.demo.DemoSupport;
import com.kaleblangley.haikalat.runtime.BloomSettings;
import com.kaleblangley.haikalat.runtime.ExposureMode;
import com.kaleblangley.haikalat.runtime.FrameClock;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.ToneMappingMode;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoQuality;
import com.kaleblangley.haikalat.subsystems.postprocess.GtaoSettings;
import com.kaleblangley.haikalat.subsystems.postprocess.PostProcessSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.Render3dDiagnostics;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironment;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentLoader;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrEnvironmentSettings;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glReadPixels;

/**
 * Purpose-built GTAO visual acceptance scene.
 *
 * <p>The left bay uses the GTAO map and the right bay opts out at material level while
 * sharing the same geometry, light and camera.  Both bays contain floor/wall contacts,
 * a block, a sphere, an overhang and a back-wall contact so the effect is visible without
 * relying on an incidental production scene composition.</p>
 */
public final class Render3dGtaoDemo {
    private static final String GTAO_MATERIAL_OPTOUT = "uGtaoMaterialOptOut";
    private static final int DEFAULT_WIDTH = 1440;
    private static final int DEFAULT_HEIGHT = 900;

    private Render3dGtaoDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat GTAO Contact Test | LEFT GTAO | RIGHT reference")
                .visible(false)
                .decorated(!options.hidden())
                .cursorMode(options.hidden() || !options.freeCamera()
                        ? GlfwWindow.CursorMode.NORMAL : GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(options.vsync());
            run(window, options);
        }
    }

    private static void run(GlfwWindow window, Options options) {
        RenderSettings settings = RenderSettings.builder()
                .vsync(options.vsync())
                .antiAliasingMode(AntiAliasingMode.NONE)
                .toneMappingMode(ToneMappingMode.ACES)
                .exposureMode(ExposureMode.MANUAL)
                .exposure(1.0f)
                .bloomSettings(BloomSettings.disabled())
                .sceneVisibility(true)
                .build();

        List<Material> materials = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        Throwable primaryFailure = null;
        try (FrameDriver driver = new FrameDriver(settings);
             PbrEnvironment environment = PbrEnvironmentLoader.load(driver.device(),
                     Render3dGtaoDemo.class, "/environments/pbr/studio-small.hdr",
                     PbrEnvironmentSettings.quality(options.environmentQuality()));
             PbrFallbackTextures fallbacks = new PbrFallbackTextures();
             ShaderProgram pbrShader = ShaderProgram.fromResource(Render3dGtaoDemo.class,
                     "/shaders/render3d/pbr/pbr-forward.vert",
                     "/shaders/render3d/pbr/pbr-forward.frag");
             Mesh box = Mesh.from(contactBoxMesh());
             Mesh sphere = Mesh.from(PbrSphereMesh.create(28, 18))) {
            meshes.add(box);
            meshes.add(sphere);
            environment.intensity(1.8f);

            Material floor = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.34f, 0.37f, 0.42f, 1.0f), 0.0f, 0.92f));
            Material wall = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.52f, 0.56f, 0.62f, 1.0f), 0.0f, 0.82f));
            Material red = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.90f, 0.12f, 0.055f, 1.0f), 0.12f, 0.34f));
            Material gold = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.98f, 0.62f, 0.08f, 1.0f), 0.65f, 0.24f));
            Material blue = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.08f, 0.34f, 0.95f, 1.0f), 0.28f, 0.30f));
            Material white = own(materials, material(pbrShader, fallbacks,
                    new Vector4f(0.82f, 0.86f, 0.92f, 1.0f), 0.0f, 0.42f));

            Camera camera = camera(options.view());
            Scene scene = new Scene(camera);
            addBay(scene, box, sphere, floor, wall, red, gold, blue, white, -4.45f, true,
                    options.preview());
            addBay(scene, box, sphere, floor, wall, red, gold, blue, white, 4.45f, false,
                    false);

            // A non-shadowing key light keeps direct illumination stable; GTAO only changes
            // indirect IBL, so the paired bays expose the intended energy relationship.
            scene.addLight(SceneLight.directional(new Vector3f(-0.42f, -1.0f, -0.36f),
                    new Vector3f(1.0f, 0.94f, 0.86f), 1.65f));
            scene.addLight(SceneLight.point(new Vector3f(0.0f, 5.8f, 1.5f),
                    new Vector3f(0.52f, 0.68f, 1.0f), 18.0f, 24.0f));

            RenderPipeline pipeline = new RenderPipeline(window, scene, null, settings, environment)
                    .postProcessSettings(PostProcessSettings.builder()
                            .gtao(options.gtaoSettings())
                            .build());
            try {
                pipeline.build();
                if (!options.hidden()) window.show();
                renderLoop(window, driver, pipeline, scene.camera(), options);
            } finally {
                pipeline.close();
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = closeReverse(materials);
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void addBay(Scene scene, Mesh box, Mesh sphere,
                               Material floor, Material wall, Material red,
                               Material gold, Material blue, Material white,
                               float centerX, boolean gtaoSide, boolean preview) {
        // Each bay is isolated by a center divider and an outer wall.  The same layout is
        // repeated on both sides; only the material-level GTAO opt-out differs.
        addBox(scene, box, floor, centerX, -2.25f, -8.6f, 8.3f, 0.4f, 18.0f, gtaoSide, preview);
        addBox(scene, box, wall, centerX, 1.75f, -17.2f, 8.3f, 8.0f, 0.4f, gtaoSide, preview);
        addBox(scene, box, wall, centerX - 4.15f, 1.75f, -8.6f, 0.4f, 8.0f, 18.0f, gtaoSide, preview);
        addBox(scene, box, wall, centerX + 4.15f, 1.75f, -8.6f, 0.4f, 8.0f, 18.0f, gtaoSide, preview);

        // The large block sits on the floor, the sphere intersects its lower corner, and
        // the blue ledge creates a second underside/back-wall contact.
        addBox(scene, box, red, centerX, -1.0f, -8.5f, 2.65f, 2.4f, 2.65f, gtaoSide, preview);
        addSphere(scene, sphere, gold, centerX - 1.35f, -1.15f, -7.4f,
                1.05f, gtaoSide, preview);
        addBox(scene, box, blue, centerX + 1.75f, 0.0f, -11.7f,
                3.2f, 0.28f, 1.7f, gtaoSide, preview);
        addBox(scene, box, white, centerX + 1.75f, -1.25f, -11.7f,
                1.15f, 1.5f, 1.15f, gtaoSide, preview);
        addBox(scene, box, gold, centerX - 2.0f, 0.0f, -16.65f,
                1.5f, 3.6f, 0.9f, gtaoSide, preview);
    }

    private static Camera camera(String view) {
        return switch (view) {
            case "left" -> new Camera(new Vector3f(-4.45f, 2.9f, 10.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -10.0f);
            case "close" -> new Camera(new Vector3f(-4.45f, 2.0f, 4.0f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -8.0f);
            default -> new Camera(new Vector3f(0.0f, 3.8f, 13.5f),
                    new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -11.0f);
        };
    }

    private static void addBox(Scene scene, Mesh mesh, Material material,
                               float x, float y, float z, float sx, float sy, float sz,
                               boolean gtaoSide, boolean preview) {
        scene.add(MeshRenderer.of(mesh, instance(material, gtaoSide, preview),
                Transform.at(x, y, z).scale(sx, sy, sz)));
    }

    private static void addSphere(Scene scene, Mesh mesh, Material material,
                                  float x, float y, float z, float scale,
                                  boolean gtaoSide, boolean preview) {
        scene.add(MeshRenderer.of(mesh, instance(material, gtaoSide, preview),
                Transform.at(x, y, z).scale(scale)));
    }

    private static MaterialInstance instance(Material material, boolean gtaoSide, boolean preview) {
        return material.createInstance()
                .setInt(GTAO_MATERIAL_OPTOUT, gtaoSide ? 0 : 1)
                .setInt("uGtaoPreview", preview ? 1 : 0);
    }

    private static Material material(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                     Vector4f color, float metallic, float roughness) {
        return PbrMaterials.createWithBindings(shader,
                new PbrMaterialProperties(color, metallic, roughness, 1.0f, 1.0f,
                        new Vector3f(), Map.of()), Map.of(), fallbacks,
                CullMode.BACK, false, false, 0.0f, BlendMode.OPAQUE);
    }

    private static <T> T own(List<T> owner, T resource) {
        owner.add(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    private static void renderLoop(GlfwWindow window, FrameDriver driver,
                                   RenderPipeline pipeline, Camera camera, Options options) {
        FrameClock clock = new FrameClock();
        int frame = 0;
        while (!window.shouldClose()
                && (options.frames() <= 0 || frame < options.frames())) {
            window.pollEvents();
            float delta = options.hidden() ? 1.0f / 60.0f : clock.tick().deltaSeconds();
            if (options.freeCamera() && !options.hidden()) {
                DemoSupport.updateFreeCamera(window, camera, delta);
            } else if (window.isKeyDown(GLFW_KEY_ESCAPE)) {
                window.requestClose();
            }
            if (window.consumeResize()) pipeline.resize(window.width(), window.height());

            driver.beginFrame();
            try {
                pipeline.execute(driver.device(), delta);
                driver.recordGraph(pipeline.graph());
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(pipeline.graph(), failure);
                throw failure;
            }
            if (options.capturePath() != null && options.frames() > 0
                    && frame + 1 == options.frames()) {
                captureFrame(window.width(), window.height(), options.capturePath());
            }
            driver.present(window::swapBuffers);
            if (!options.hidden() && frame % 30 == 0) {
                updateTitle(window, pipeline.lastRender3dDiagnostics());
            }
            frame++;
        }
        Render3dDiagnostics diagnostics = pipeline.lastRender3dDiagnostics();
        printSummary(diagnostics, frame, driver.statistics().averageFps());
        GlDebug.assertNoError("Render3dGtaoDemo");
    }

    private static void updateTitle(GlfwWindow window, Render3dDiagnostics diagnostics) {
        if (!diagnostics.available()) return;
        var ao = diagnostics.ambientOcclusion();
        window.setTitle(String.format(Locale.ROOT,
                "GTAO Contact Test | LEFT GTAO / RIGHT reference | %s %s %.2f/%.2f | %dx%d",
                ao.enabled() ? "ON" : "OFF", ao.quality(), ao.strength(), ao.radius(),
                window.width(), window.height()));
    }

    private static void printSummary(Render3dDiagnostics diagnostics, int frames, double fps) {
        if (!diagnostics.available()) {
            System.out.printf(Locale.ROOT, "Render3dGtaoDemo | frames=%d diagnostics=unavailable%n", frames);
            return;
        }
        var ao = diagnostics.ambientOcclusion();
        System.out.printf(Locale.ROOT,
                "Render3dGtaoDemo | frames=%d fps=%.2f gtao=%s quality=%s radius=%.2f "
                        + "strength=%.2f history=%s depthDraws=%d half=%dx%d%n",
                frames, fps, ao.enabled(), ao.quality(), ao.radius(), ao.strength(),
                ao.historyValid(), ao.depthPrepassDraws(), ao.halfWidth(), ao.halfHeight());
        if (diagnostics.failureStage() != null && !diagnostics.failureStage().isEmpty()) {
            throw new IllegalStateException("GTAO visual demo recorded failure stage "
                    + diagnostics.failureStage());
        }
    }

    private static void captureFrame(int width, int height, String capturePath) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = Byte.toUnsignedInt(pixels.get(offset));
                int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                image.setRGB(x, height - 1 - y, alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        Path output = Path.of(capturePath).toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            System.out.println("GTAO visual capture: " + output);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to capture GTAO visual demo to " + output,
                    failure);
        }
    }

    private static MeshData contactBoxMesh() {
        float[][][] faces = {
                {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},
                {{.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}, {-.5f, .5f, -.5f}, {.5f, .5f, -.5f}},
                {{-.5f, -.5f, -.5f}, {-.5f, -.5f, .5f}, {-.5f, .5f, .5f}, {-.5f, .5f, -.5f}},
                {{.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}},
                {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, .5f, -.5f}, {-.5f, .5f, -.5f}},
                {{-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}}
        };
        float[][] normals = {
                {0, 0, 1}, {0, 0, -1}, {-1, 0, 0},
                {1, 0, 0}, {0, 1, 0}, {0, -1, 0}
        };
        float[][] tangents = {
                {1, 0, 0, 1}, {-1, 0, 0, 1}, {0, 0, -1, 1},
                {0, 0, 1, 1}, {1, 0, 0, 1}, {1, 0, 0, 1}
        };
        float[] vertices = new float[6 * 4 * 12];
        int[] indices = new int[6 * 6];
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        int vertexCursor = 0;
        int indexCursor = 0;
        for (int face = 0; face < faces.length; face++) {
            for (int corner = 0; corner < 4; corner++) {
                float[] position = faces[face][corner];
                float[] normal = normals[face];
                float[] tangent = tangents[face];
                vertices[vertexCursor++] = position[0];
                vertices[vertexCursor++] = position[1];
                vertices[vertexCursor++] = position[2];
                vertices[vertexCursor++] = uv[corner][0];
                vertices[vertexCursor++] = uv[corner][1];
                vertices[vertexCursor++] = normal[0];
                vertices[vertexCursor++] = normal[1];
                vertices[vertexCursor++] = normal[2];
                vertices[vertexCursor++] = tangent[0];
                vertices[vertexCursor++] = tangent[1];
                vertices[vertexCursor++] = tangent[2];
                vertices[vertexCursor++] = tangent[3];
            }
            int base = face * 4;
            indices[indexCursor++] = base;
            indices[indexCursor++] = base + 1;
            indices[indexCursor++] = base + 2;
            indices[indexCursor++] = base;
            indices[indexCursor++] = base + 2;
            indices[indexCursor++] = base + 3;
        }
        VertexLayout layout = VertexLayout.interleaved(12 * Float.BYTES,
                VertexAttribute.builder().index(0).size(3).offsetBytes(0)
                        .semantic(VertexSemantic.POSITION).build(),
                VertexAttribute.builder().index(1).size(2).offsetBytes(3L * Float.BYTES)
                        .semantic(VertexSemantic.TEXCOORD_0).build(),
                VertexAttribute.builder().index(2).size(3).offsetBytes(5L * Float.BYTES)
                        .semantic(VertexSemantic.NORMAL).build(),
                VertexAttribute.builder().index(3).size(4).offsetBytes(8L * Float.BYTES)
                        .semantic(VertexSemantic.TANGENT).build());
        return MeshData.indexed("gtao-contact-box", vertices, indices, layout);
    }

    private static RuntimeException closeReverse(List<? extends AutoCloseable> resources) {
        RuntimeException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception closeFailure) {
                RuntimeException runtime = closeFailure instanceof RuntimeException existing
                        ? existing : new IllegalStateException("failed to close GTAO material", closeFailure);
                if (failure == null) failure = runtime;
                else failure.addSuppressed(runtime);
            }
        }
        return failure;
    }

    private record Options(boolean hidden, int frames, int width, int height, boolean vsync,
                           boolean gtao, GtaoQuality quality, float radius, float strength,
                           float thickness, String environmentQuality, boolean preview,
                           boolean freeCamera, String view, String capturePath) {
        static Options parse(String[] arguments) {
            boolean hidden = false;
            int frames = -1;
            int width = DEFAULT_WIDTH;
            int height = DEFAULT_HEIGHT;
            boolean vsync = false;
            boolean gtao = true;
            GtaoQuality quality = GtaoQuality.MEDIUM;
            // Conservative visual defaults keep contact transitions readable when the
            // free camera is brought close to a wall or prop.  Stronger values remain
            // available through the command-line tuning flags.
            float radius = 0.9f;
            float strength = 1.25f;
            float thickness = 0.22f;
            String environmentQuality = "test";
            boolean preview = false;
            boolean freeCamera = false;
            String view = "paired";
            String capturePath = null;
            for (String argument : Objects.requireNonNull(arguments, "arguments")) {
                if (argument.equals("--hidden")) hidden = true;
                else if (argument.equals("--free-camera")) freeCamera = true;
                else if (argument.equals("--vsync")) vsync = true;
                else if (argument.equals("--no-vsync")) vsync = false;
                else if (argument.equals("--gtao")) gtao = true;
                else if (argument.equals("--no-gtao")) gtao = false;
                else if (argument.equals("--preview")) preview = true;
                else if (argument.startsWith("--frames=")) frames = positive(argument, "--frames=");
                else if (argument.startsWith("--size=")) {
                    int[] size = parseSize(argument.substring("--size=".length()));
                    width = size[0];
                    height = size[1];
                } else if (argument.startsWith("--gtao-quality=")) {
                    quality = GtaoQuality.valueOf(argument.substring("--gtao-quality=".length())
                            .toUpperCase(Locale.ROOT));
                } else if (argument.startsWith("--gtao-radius=")) {
                    radius = finitePositive(argument, "--gtao-radius=");
                } else if (argument.startsWith("--gtao-strength=")) {
                    strength = finiteRange(argument, "--gtao-strength=", 0.0f, 4.0f);
                } else if (argument.startsWith("--gtao-thickness=")) {
                    thickness = finitePositive(argument, "--gtao-thickness=");
                } else if (argument.startsWith("--environment-quality=")) {
                    environmentQuality = argument.substring("--environment-quality=".length())
                            .toLowerCase(Locale.ROOT);
                    if (!environmentQuality.equals("test") && !environmentQuality.equals("default")) {
                        throw new IllegalArgumentException("environment-quality must be test or default");
                    }
                } else if (argument.startsWith("--view=")) {
                    view = argument.substring("--view=".length()).toLowerCase(Locale.ROOT);
                    if (!view.equals("paired") && !view.equals("left") && !view.equals("close")) {
                        throw new IllegalArgumentException("view must be paired, left or close");
                    }
                } else if (argument.startsWith("--capture=")) {
                    capturePath = argument.substring("--capture=".length());
                    if (capturePath.isBlank()) throw new IllegalArgumentException("--capture path is blank");
                } else {
                    throw new IllegalArgumentException("Unknown Render3dGtaoDemo argument: " + argument);
                }
            }
            if (hidden && frames < 0) frames = 120;
            if (frames == 0) throw new IllegalArgumentException("frames must be positive");
            if (capturePath != null && frames < 1) {
                throw new IllegalArgumentException("--capture requires a positive --frames value");
            }
            if (thickness > radius) {
                throw new IllegalArgumentException("gtao-thickness must be <= gtao-radius");
            }
            return new Options(hidden, frames, width, height, vsync, gtao, quality, radius,
                    strength, thickness, environmentQuality, preview, freeCamera, view, capturePath);
        }

        GtaoSettings gtaoSettings() {
            return new GtaoSettings(gtao, quality, radius, strength, thickness,
                    true, 0.75f, 0.02f);
        }

        private static int positive(String argument, String prefix) {
            try {
                int value = Integer.parseInt(argument.substring(prefix.length()));
                if (value <= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(prefix + " must be positive", failure);
            }
        }

        private static float finitePositive(String argument, String prefix) {
            try {
                float value = Float.parseFloat(argument.substring(prefix.length()));
                if (!Float.isFinite(value) || value <= 0.0f) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(prefix + " must be finite and positive", failure);
            }
        }

        private static float finiteRange(String argument, String prefix, float min, float max) {
            try {
                float value = Float.parseFloat(argument.substring(prefix.length()));
                if (!Float.isFinite(value) || value < min || value > max) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(prefix + " must be finite and in ["
                        + min + ", " + max + "]", failure);
            }
        }

        private static int[] parseSize(String value) {
            String[] parts = value.toLowerCase(Locale.ROOT).split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException("--size expects WIDTHxHEIGHT");
            try {
                int w = Integer.parseInt(parts[0]);
                int h = Integer.parseInt(parts[1]);
                if (w <= 0 || h <= 0) throw new NumberFormatException();
                return new int[]{w, h};
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("--size expects positive WIDTHxHEIGHT", failure);
            }
        }
    }
}
