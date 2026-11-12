package com.kaleblangley.haikalat.subsystems.render3d.ddgi;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexSemantic;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrameBuilder;
import com.kaleblangley.haikalat.subsystems.render3d.scene.TemporalFrameState;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glReadPixels;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.glReadBuffer;

/**
 * Real-GL contract for the dynamic DDGI probe owner and its production indirect
 * consumer.
 *
 * <p>The fixture rasterizes real floor/box/plate geometry into the probe depth
 * atlas. It proves the ray mechanism is dynamic rather than a static bake:
 * moving the box changes the recorded probe depth and the relit irradiance; a
 * frozen control read (no update after the move) stays bit-identical; the
 * per-frame budget only writes the scheduled probe tile; hysteresis matches the
 * independent temporal blend from {@link DdgiProbeReference}; and the indirect
 * pass rejects receivers outside the probe bounds and receivers hidden behind
 * a surface recorded by the probe depth atlas.</p>
 *
 * <p>These cases do not claim GL PASS on their own. The file is guarded by
 * {@code haikalat.glSmoke} and must be executed by the serialized GPU queue with
 * a real OpenGL 4.6 context; the feature report marks it {@code NOT_EVALUATED}
 * until that run happens. Performance, real demo scenes, MSAA depth, instanced
 * casters and multi-frame temporal convergence are out of this class's scope.</p>
 */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class DdgiProbePassGlTest {
    private static final int SIZE = 64;
    private static final float BACKGROUND_RED = 0.02f;
    private static final float BACKGROUND_GREEN = 0.03f;
    private static final float BACKGROUND_BLUE = 0.04f;
    private static final float PIXEL_TOLERANCE = 2.0e-2f;
    private static final float BACKGROUND_TOLERANCE = 2.0e-3f;
    private static final float FLOOR_HEIGHT = 1.0f;
    private static final Vector3f LIGHT_DIRECTION = new Vector3f(0.0f, -1.0f, 0.0f);
    private static final Vector3f LIGHT_RADIANCE = new Vector3f(2.0f, 2.0f, 2.0f);
    private static final Vector3f BRIGHTER_LIGHT_RADIANCE = new Vector3f(6.0f, 6.0f, 6.0f);

    @Test
    void movingObjectChangesProbeDepthAndIrradiance() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create()) {
                DdgiSettings settings = probeSettings();
                GlRenderDevice device = new GlRenderDevice();
                try (DdgiProbeVolume volume = new DdgiProbeVolume(settings)) {
                    recordUpdate(device, volume,
                            frame(geometry, null, new Matrix4f(), 0));
                    float depthWithoutBox = probeDownDepth(volume, 0);
                    float[] atlasWithoutBox = readIrradiance(volume);
                    assertTrue(depthWithoutBox > 0.0f && depthWithoutBox < 1.0f,
                            "the floor must be recorded in the probe depth atlas");
                    float expectedDown = (float) DdgiProbeReference.updateRay(settings, 0,
                            0.0, -1.0, 0.0,
                            (probe, x, y, z) -> 1.0,
                            (x, y, z) -> new double[]{0.0, 1.0, 0.0},
                            0.0, -1.0, 0.0, LIGHT_RADIANCE.x,
                            settings.surfaceAlbedo(), settings.skyRadiance().x);
                    assertEquals(expectedDown, downFaceRadiance(settings, atlasWithoutBox),
                            2.0e-2f,
                            "the first relight must match the independent direct-radiance oracle");

                    Matrix4f boxTranslation = new Matrix4f().translation(2.0f, 1.25f, 2.0f);
                    recordUpdate(device, volume,
                            frame(geometry, geometry.box, boxTranslation, 1));
                    float depthWithBox = probeDownDepth(volume, 0);
                    float[] atlasWithBox = readIrradiance(volume);

                    assertTrue(depthWithBox < depthWithoutBox - 1.0e-2f,
                            "moving a box under the probe must move the recorded depth closer; "
                                    + depthWithoutBox + " -> " + depthWithBox);
                    assertTrue(maximumDifference(atlasWithoutBox, atlasWithBox) > 2.0e-2f,
                            "the moving object must change probe irradiance, not only depth");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void staticBakeControlStaysFrozenUntilTheNextUpdate() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create()) {
                DdgiSettings settings = probeSettings();
                GlRenderDevice device = new GlRenderDevice();
                try (DdgiProbeVolume volume = new DdgiProbeVolume(settings)) {
                    Matrix4f firstPosition = new Matrix4f().translation(1.2f, 1.25f, 2.0f);
                    recordUpdate(device, volume,
                            frame(geometry, geometry.box, firstPosition, 0));
                    float[] baked = readIrradiance(volume);

                    Matrix4f secondPosition = new Matrix4f().translation(2.6f, 1.25f, 2.0f);
                    SceneFrame moved = frame(geometry, geometry.box, secondPosition, 1);
                    float[] frozen = readIrradiance(volume);
                    assertArrayEquals(baked, frozen, 0.0f,
                            "without an update the probe atlas must stay a frozen bake control");

                    recordUpdate(device, volume, moved);
                    float[] afterUpdate = readIrradiance(volume);
                    assertTrue(maximumDifference(baked, afterUpdate) > 1.0e-2f,
                            "the same scene change must update the probes through the dynamic path");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void updateBudgetRefreshesOnlyScheduledProbes() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create()) {
                DdgiSettings settings = DdgiSettings.builder()
                        .enabled(true)
                        .gridOrigin(0.0f, 0.0f, 0.0f)
                        .spacingMetres(4.0f)
                        .probes(2, 1, 1)
                        .updateBudget(1)
                        .hysteresis(0.0f)
                        .maximumRayDistance(8.0f)
                        .raySteps(12)
                        .depthFaceResolution(8)
                        .borderFadeMetres(0.5f)
                        .surfaceAlbedo(0.8f)
                        .skyRadiance(0.10f, 0.10f, 0.10f)
                        .build();
                GlRenderDevice device = new GlRenderDevice();
                SceneFrame probeFrame = frame(geometry, null, new Matrix4f(), 0);
                try (DdgiProbeVolume volume = new DdgiProbeVolume(settings)) {
                    recordUpdate(device, volume, probeFrame);
                    assertEquals(1, volume.updatedProbeCount());
                    assertEquals(0, volume.updatedProbeIndex(0));
                    float[] first = readIrradiance(volume);
                    assertTrue(probeTileMaximum(settings, first, 0) > 1.0e-2f,
                            "the scheduled probe must receive irradiance");
                    assertEquals(0.0f, probeTileMaximum(settings, first, 1), 0.0f,
                            "the unscheduled probe tile must stay untouched");

                    recordUpdate(device, volume, probeFrame);
                    assertEquals(1, volume.updatedProbeIndex(0));
                    float[] second = readIrradiance(volume);
                    assertTrue(probeTileMaximum(settings, second, 1) > 1.0e-2f,
                            "the next frame must refresh the next probe in the rotation");
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void indirectPassQueriesInsideBoundsAndRejectsOutside() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create();
                 Framebuffer sceneSource = cameraSource();
                 Texture2D normal = constantUpNormal()) {
                DdgiSettings settings = probeSettings();
                GlRenderDevice device = new GlRenderDevice();
                try (DdgiProbeVolume volume = new DdgiProbeVolume(settings)) {
                    recordUpdate(device, volume,
                            frame(geometry, null, new Matrix4f(), 0));
                    float probeRadiance = upFacingProbeRadiance(settings, readIrradiance(volume));

                    Camera camera = camera();
                    Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());
                    TemporalFrameState.FrameParameters cameraFrame = frameParameters(
                            camera, projection, view, SIZE);
                    renderCameraFloor(device, sceneSource, geometry, projection, view);

                    try (DdgiIndirectPass pass = new DdgiIndirectPass(volume, SIZE, SIZE)) {
                        recordIndirect(device, pass, sceneSource, normal, cameraFrame);
                        FloatBuffer pixels = readColor(pass);
                        float[] depth = readSceneDepth(sceneSource);

                        int inside = findPixel(depth, cameraFrame, position ->
                                position.x >= 1.5f && position.x <= 2.5f
                                        && position.z >= 1.5f && position.z <= 2.5f);
                        int outside = findPixel(depth, cameraFrame, position ->
                                position.x <= -0.5f || position.x >= 4.5f
                                        || position.z <= -0.5f || position.z >= 4.5f);
                        Vector3f insideWorld = worldAt(depth, cameraFrame, inside);
                        double expected = DdgiProbeReference.sample(settings,
                                insideWorld.x, insideWorld.y, insideWorld.z, 0.0, 1.0, 0.0,
                                new double[]{probeRadiance}, DdgiProbeReference.NO_OCCLUDER)
                                * settings.surfaceAlbedo() * settings.intensity();
                        assertPixel(BACKGROUND_RED + (float) expected,
                                BACKGROUND_GREEN + (float) expected,
                                BACKGROUND_BLUE + (float) expected,
                                pixels, inside, PIXEL_TOLERANCE,
                                "inside-grid receiver must query the probe atlas");
                        assertPixel(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE,
                                pixels, outside, BACKGROUND_TOLERANCE,
                                "outside-grid receiver must receive exactly nothing");
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                }
            }
        }
    }

    @Test
    void indirectPassRejectsProbeOccludedReceivers() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create();
                 Framebuffer sceneSource = cameraSource();
                 Texture2D normal = constantUpNormal()) {
                DdgiSettings settings = probeSettings();
                GlRenderDevice device = new GlRenderDevice();
                try (DdgiProbeVolume volume = new DdgiProbeVolume(settings)) {
                    recordUpdate(device, volume,
                            frame(geometry, geometry.plate, new Matrix4f(), 0));

                    Camera camera = camera();
                    Matrix4f projection = CameraProjection.stable(camera, SIZE, SIZE,
                            new Matrix4f());
                    Matrix4f view = camera.getViewMatrix(new Matrix4f());
                    TemporalFrameState.FrameParameters cameraFrame = frameParameters(
                            camera, projection, view, SIZE);
                    renderCameraFloor(device, sceneSource, geometry, projection, view);

                    try (DdgiIndirectPass pass = new DdgiIndirectPass(volume, SIZE, SIZE)) {
                        recordIndirect(device, pass, sceneSource, normal, cameraFrame);
                        FloatBuffer pixels = readColor(pass);
                        float[] depth = readSceneDepth(sceneSource);

                        int occluded = findPixel(depth, cameraFrame, position ->
                                position.x >= 1.8f && position.x <= 2.2f
                                        && position.z >= 1.8f && position.z <= 2.2f);
                        int visible = findPixel(depth, cameraFrame, position ->
                                position.x >= 0.3f && position.x <= 0.7f
                                        && position.z >= 1.8f && position.z <= 2.2f);
                        assertPixel(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE,
                                pixels, occluded, BACKGROUND_TOLERANCE,
                                "receiver behind the probe-recorded plate must receive nothing");
                        float occludedRed = pixels.get(occluded * 4);
                        float litRed = pixels.get(visible * 4);
                        assertTrue(litRed > BACKGROUND_RED + 5.0e-3f,
                                "an unoccluded receiver must still receive indirect light, got "
                                        + litRed + " vs occluded " + occludedRed);
                        assertTrue(litRed > occludedRed,
                                "occluded receivers must be darker than visible receivers");
                        assertEquals(GL_NO_ERROR, glGetError());
                    }
                }
            }
        }
    }

    @Test
    void hysteresisMatchesTheIndependentTemporalBlend() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            try (Geometry geometry = Geometry.create()) {
                DdgiSettings blended = probeSettings().toBuilder()
                        .hysteresis(0.5f).build();
                DdgiSettings raw = blended.toBuilder().hysteresis(0.0f).build();
                GlRenderDevice device = new GlRenderDevice();
                SceneFrame probeFrame = frame(geometry, null, new Matrix4f(), 0);
                try (DdgiProbeVolume blendedVolume = new DdgiProbeVolume(blended);
                     DdgiProbeVolume rawVolume = new DdgiProbeVolume(raw)) {
                    recordUpdate(device, blendedVolume, probeFrame);
                    recordUpdate(device, rawVolume, probeFrame);
                    float[] first = readIrradiance(blendedVolume);

                    recordUpdate(device, blendedVolume, probeFrame, BRIGHTER_LIGHT_RADIANCE);
                    recordUpdate(device, rawVolume, probeFrame, BRIGHTER_LIGHT_RADIANCE);
                    float[] blendedSecond = readIrradiance(blendedVolume);
                    float[] rawSecond = readIrradiance(rawVolume);

                    int compared = 0;
                    for (int index = 0; index < first.length; index += 4) {
                        float expected = (float) DdgiProbeReference.hysteresis(
                                first[index], rawSecond[index], 0.5, 32.0);
                        if (Math.abs(expected) < 0.02f
                                && Math.abs(first[index]) < 0.02f) {
                            continue;
                        }
                        assertEquals(expected, blendedSecond[index], 5.0e-3f,
                                "temporal blend must match the independent hysteresis formula");
                        compared++;
                    }
                    assertTrue(compared >= 8, "expected several changing irradiance texels, got "
                            + compared);
                    assertEquals(GL_NO_ERROR, glGetError());
                }
            }
        }
    }

    @Test
    void disabledSettingsAllocateNothingAndCloseIsIdempotent() {
        try (GlfwWindow window = hiddenWindow()) {
            bind(window);
            int liveBefore = GlDebug.resources().liveResources().size();
            assertThrows(IllegalArgumentException.class,
                    () -> new DdgiProbeVolume(DdgiSettings.disabled()),
                    "disabled DDGI must not create a probe owner");
            assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                    "disabled settings are an exact no-op");

            DdgiProbeVolume volume = new DdgiProbeVolume(probeSettings());
            DdgiIndirectPass pass = new DdgiIndirectPass(volume, 32, 32);
            try {
                int firstTexture = pass.colorTexture();
                assertTrue(firstTexture > 0);
                pass.resize(48, 48);
                assertEquals(48, pass.width());
                assertEquals(48, pass.height());
                assertNotEquals(firstTexture, pass.colorTexture());
                assertThrows(IllegalArgumentException.class, () -> pass.resize(0, 32));
                assertEquals(48, pass.width(),
                        "a failed resize must keep the previous target active");
            } finally {
                pass.close();
            }
            pass.close();
            assertTrue(pass.isClosed());
            assertEquals(0, pass.colorTexture());
            assertThrows(GlException.class, () -> pass.resize(32, 32));
            assertTrue(!volume.isClosed(), "closing the consumer must not close the borrowed volume");
            volume.close();
            volume.close();
            assertTrue(volume.isClosed());
            assertEquals(0, volume.irradianceTexture());
            assertEquals(0, volume.probeDepthTexture());
            assertThrows(GlException.class, volume::ensureOpen);
            assertEquals(liveBefore, GlDebug.resources().liveResources().size(),
                    "closing the volume and the indirect pass must release every owned resource");
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static DdgiSettings probeSettings() {
        return DdgiSettings.builder()
                .enabled(true)
                .intensity(1.0f)
                .surfaceAlbedo(0.8f)
                .skyRadiance(0.10f, 0.10f, 0.10f)
                .gridOrigin(0.0f, 0.0f, 0.0f)
                .spacingMetres(4.0f)
                .probes(1, 1, 1)
                .updateBudget(1)
                .hysteresis(0.0f)
                .maximumRayDistance(6.0f)
                .raySteps(16)
                .depthFaceResolution(8)
                .borderFadeMetres(0.5f)
                .maximumIrradiance(32.0f)
                .build();
    }

    private static void recordUpdate(GlRenderDevice device, DdgiProbeVolume volume,
                                     SceneFrame frame) {
        recordUpdate(device, volume, frame, LIGHT_RADIANCE);
    }

    private static void recordUpdate(GlRenderDevice device, DdgiProbeVolume volume,
                                     SceneFrame frame, Vector3f lightRadiance) {
        CommandBuffer commands = device.createCommandBuffer();
        volume.recordUpdate(commands, frame, LIGHT_DIRECTION, lightRadiance);
        device.execute(commands);
    }

    private static void recordIndirect(GlRenderDevice device, DdgiIndirectPass pass,
                                       Framebuffer source, Texture2D normal,
                                       TemporalFrameState.FrameParameters frame) {
        CommandBuffer commands = device.createCommandBuffer();
        pass.record(commands, source, source.depthAttachment(), normal.id(), frame);
        device.execute(commands);
    }

    private static SceneFrame frame(Geometry geometry, Mesh extra, Matrix4f translation,
                                    int frameIndex) {
        Scene scene = new Scene(new Camera());
        scene.add(SceneObject.fixed(geometry.floor, geometry.material, new Matrix4f(), true));
        if (extra != null) {
            scene.add(SceneObject.fixed(extra, geometry.material, translation, true));
        }
        return new SceneFrameBuilder().build(scene, SIZE, SIZE, new Matrix4f(),
                false, false, frameIndex);
    }

    private static float probeDownDepth(DdgiProbeVolume volume, int probe) {
        int resolution = volume.settings().depthFaceResolution();
        int face = 3;
        int x = (probe * DdgiSettings.FACE_COUNT + face) * resolution + resolution / 2;
        int y = resolution / 2;
        volume.probeDepthTarget().bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(1);
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        return depth.get(0);
    }

    private static float[] readIrradiance(DdgiProbeVolume volume) {
        Framebuffer target = volume.irradianceTarget();
        target.bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(target.width() * target.height() * 4);
        glReadPixels(0, 0, target.width(), target.height(), GL_RGBA, GL_FLOAT, pixels);
        float[] result = new float[pixels.capacity()];
        pixels.get(result);
        return result;
    }

    private static float upFacingProbeRadiance(DdgiSettings settings, float[] atlas) {
        int resolution = DdgiSettings.IRRADIANCE_FACE_RESOLUTION;
        int face = 2;
        int faceX = face % DdgiSettings.IRRADIANCE_TILE_COLUMNS;
        int faceY = face / DdgiSettings.IRRADIANCE_TILE_COLUMNS;
        int baseX = faceX * resolution;
        int baseY = faceY * resolution;
        int width = settings.irradianceAtlasWidth();
        // The +Y direction maps to the exact centre of the face tile, so the
        // bilinear shader samples the four centre texels with 1/4 weights.
        int low = resolution / 2 - 1;
        float sum = 0.0f;
        for (int y = low; y <= low + 1; y++) {
            for (int x = low; x <= low + 1; x++) {
                int atlasX = baseX + x;
                int atlasY = baseY + y;
                sum += atlas[(atlasY * width + atlasX) * 4];
            }
        }
        return sum / 4.0f;
    }

    private static float downFaceRadiance(DdgiSettings settings, float[] atlas) {
        int resolution = DdgiSettings.IRRADIANCE_FACE_RESOLUTION;
        int face = 3;
        int faceX = face % DdgiSettings.IRRADIANCE_TILE_COLUMNS;
        int faceY = face / DdgiSettings.IRRADIANCE_TILE_COLUMNS;
        int atlasX = faceX * resolution + resolution / 2;
        int atlasY = faceY * resolution + resolution / 2;
        int width = settings.irradianceAtlasWidth();
        return atlas[(atlasY * width + atlasX) * 4];
    }

    private static float probeTileMaximum(DdgiSettings settings, float[] atlas, int probe) {
        int width = settings.irradianceAtlasWidth();
        int tileWidth = DdgiSettings.IRRADIANCE_TILE_WIDTH;
        float maximum = 0.0f;
        for (int y = 0; y < DdgiSettings.IRRADIANCE_TILE_HEIGHT; y++) {
            for (int x = 0; x < tileWidth; x++) {
                int atlasX = probe * tileWidth + x;
                maximum = Math.max(maximum, atlas[(y * width + atlasX) * 4]);
            }
        }
        return maximum;
    }

    private static float maximumDifference(float[] first, float[] second) {
        assertEquals(first.length, second.length);
        float maximum = 0.0f;
        for (int index = 0; index < first.length; index++) {
            maximum = Math.max(maximum, Math.abs(first[index] - second[index]));
        }
        return maximum;
    }

    private static Framebuffer cameraSource() {
        return Framebuffer.fromDescriptor(FramebufferDescriptor.builder(SIZE, SIZE)
                .colorTexture(RenderFormat.RGBA16F)
                .depthTexture()
                .build());
    }

    private static Texture2D constantUpNormal() {
        // Octahedral encoding of world +Y is (0.5, 1.0).
        return Texture2D.fromRgba8(1, 1,
                new byte[]{(byte) 128, (byte) 255, (byte) 128, (byte) 255},
                TextureColorSpace.LINEAR);
    }

    private static Camera camera() {
        return new Camera(new Vector3f(2.0f, 8.0f, 8.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), -90.0f, -45.0f);
    }

    private static void renderCameraFloor(GlRenderDevice device, Framebuffer source,
                                          Geometry geometry, Matrix4f projection,
                                          Matrix4f view) {
        source.bind();
        glViewport(0, 0, SIZE, SIZE);
        glClearColor(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f);
        glClearDepth(1.0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        CommandBuffer commands = device.createCommandBuffer();
        commands.bindFramebuffer(source)
                .viewport(0, 0, SIZE, SIZE)
                .enableScissor(false)
                .enableBlend(false)
                .enableCullFace(false)
                .enableDepthTest(true)
                .depthMask(true)
                .bindShader(geometry.cameraShader)
                .setUniformMat4(geometry.cameraShader, "uViewProjection",
                        new Matrix4f(projection).mul(view))
                .setUniformVec4(geometry.cameraShader, "uColor",
                        new Vector4f(BACKGROUND_RED, BACKGROUND_GREEN, BACKGROUND_BLUE, 1.0f))
                .bindMesh(geometry.floor)
                .drawMesh(geometry.floor);
        device.execute(commands);
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private static TemporalFrameState.FrameParameters frameParameters(
            Camera camera, Matrix4f projection, Matrix4f view, int extent) {
        Vector3f position = camera.position();
        Vector3f front = camera.front();
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        return new TemporalFrameState.FrameParameters(
                0L, extent, extent, CameraProjection.NEAR_PLANE, CameraProjection.FAR_PLANE,
                position.x, position.y, position.z, front.x, front.y, front.z, 0.0f, 0.0f,
                projection, projection, new Matrix4f(projection).invert(), view, viewProjection,
                new Matrix4f(viewProjection).invert(), 0L);
    }

    private static float[] readSceneDepth(Framebuffer source) {
        source.bind();
        FloatBuffer depth = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        float[] result = new float[depth.capacity()];
        depth.get(result);
        return result;
    }

    private static Vector3f worldAt(float[] depth, TemporalFrameState.FrameParameters frame,
                                    int pixel) {
        int x = pixel % SIZE;
        int y = pixel / SIZE;
        float value = depth[y * SIZE + x];
        float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
        float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
        Vector4f clip = new Vector4f(ndcX, ndcY, value * 2.0f - 1.0f, 1.0f)
                .mul(frame.inverseJitteredProjection());
        clip.div(clip.w);
        Matrix4f inverseView = new Matrix4f(frame.view()).invert();
        Vector4f world = new Vector4f(clip.x, clip.y, clip.z, 1.0f).mul(inverseView);
        world.div(world.w);
        return new Vector3f(world.x, world.y, world.z);
    }

    private static int findPixel(float[] depth, TemporalFrameState.FrameParameters frame,
                                 java.util.function.Predicate<Vector3f> predicate) {
        Matrix4f inverseView = new Matrix4f(frame.view()).invert();
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float value = depth[y * SIZE + x];
                if (!(value < 0.999999f)) {
                    continue;
                }
                float ndcX = (x + 0.5f) / SIZE * 2.0f - 1.0f;
                float ndcY = (y + 0.5f) / SIZE * 2.0f - 1.0f;
                Vector4f clip = new Vector4f(ndcX, ndcY, value * 2.0f - 1.0f, 1.0f)
                        .mul(frame.inverseJitteredProjection());
                clip.div(clip.w);
                Vector4f world = new Vector4f(clip.x, clip.y, clip.z, 1.0f).mul(inverseView);
                world.div(world.w);
                Vector3f position = new Vector3f(world.x, world.y, world.z);
                if (!predicate.test(position)) {
                    continue;
                }
                float centerDistance = Math.abs(position.x - 2.0f)
                        + Math.abs(position.z - 2.0f);
                if (centerDistance < bestDistance) {
                    bestDistance = centerDistance;
                    best = y * SIZE + x;
                }
            }
        }
        assertTrue(best >= 0, "fixture camera did not cover the requested world region");
        return best;
    }

    private static FloatBuffer readColor(DdgiIndirectPass pass) {
        pass.colorTarget().bind();
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer pixels = BufferUtils.createFloatBuffer(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, pixels);
        return pixels;
    }

    private static void assertPixel(float red, float green, float blue, FloatBuffer pixels,
                                    int index, float tolerance, String message) {
        assertEquals(red, pixels.get(index * 4), tolerance, message + " red");
        assertEquals(green, pixels.get(index * 4 + 1), tolerance, message + " green");
        assertEquals(blue, pixels.get(index * 4 + 2), tolerance, message + " blue");
    }

    private static void bind(GlfwWindow window) {
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        while (glGetError() != GL_NO_ERROR) {
            // drain stale errors from context creation before asserting.
        }
    }

    private static GlfwWindow hiddenWindow() {
        return new GlfwWindow.Builder().dimensions(SIZE, SIZE)
                .title("DDGI probe pass test").visible(false).build();
    }

    /**
     * Owns the fixture geometry and the trivial material program. The floor is
     * a large horizontal plane at {@link #FLOOR_HEIGHT}; the box is a small
     * cube used to prove dynamic probe updates; the plate is a horizontal
     * occluder at y = 1.5 used only by the probe depth atlas.
     */
    private static final class Geometry implements AutoCloseable {
        private static final String CAMERA_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                uniform mat4 uViewProjection;
                void main() {
                    gl_Position = uViewProjection * vec4(aPosition, 1.0);
                }
                """;
        private static final String CAMERA_FRAGMENT = """
                #version 460 core
                uniform vec4 uColor;
                out vec4 fragColor;
                void main() {
                    fragColor = uColor;
                }
                """;
        private static final String MATERIAL_VERTEX = """
                #version 460 core
                layout(location = 0) in vec3 aPosition;
                void main() {
                    gl_Position = vec4(aPosition, 1.0);
                }
                """;
        private static final String MATERIAL_FRAGMENT = """
                #version 460 core
                void main() {
                }
                """;

        private final ShaderProgram cameraShader;
        private final ShaderProgram materialShader;
        private final Material material;
        private final Mesh floor;
        private final Mesh box;
        private final Mesh plate;

        private Geometry(ShaderProgram cameraShader, ShaderProgram materialShader,
                         Material material, Mesh floor, Mesh box, Mesh plate) {
            this.cameraShader = cameraShader;
            this.materialShader = materialShader;
            this.material = material;
            this.floor = floor;
            this.box = box;
            this.plate = plate;
        }

        static Geometry create() {
            ShaderProgram cameraShader = null;
            ShaderProgram materialShader = null;
            Material material = null;
            Mesh floor = null;
            Mesh box = null;
            Mesh plate = null;
            try {
                cameraShader = ShaderProgram.fromSources(CAMERA_VERTEX, CAMERA_FRAGMENT);
                materialShader = ShaderProgram.fromSources(MATERIAL_VERTEX,
                        MATERIAL_FRAGMENT);
                material = Material.builder(materialShader).build();
                floor = horizontalQuad(-16.0f, 16.0f, -16.0f, 16.0f, FLOOR_HEIGHT);
                box = boxMesh(1.0f, 0.5f, 1.0f);
                plate = horizontalQuad(1.6f, 2.4f, 1.6f, 2.4f, 1.5f);
                return new Geometry(cameraShader, materialShader, material, floor, box, plate);
            } catch (RuntimeException | Error failure) {
                closeQuietly(plate);
                closeQuietly(box);
                closeQuietly(floor);
                closeQuietly(cameraShader);
                closeQuietly(materialShader);
                throw failure;
            }
        }

        @Override
        public void close() {
            RuntimeException failure = close(plate, null);
            failure = close(box, failure);
            failure = close(floor, failure);
            failure = close(cameraShader, failure);
            failure = close(materialShader, failure);
            if (failure != null) {
                throw failure;
            }
        }

        private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
            if (resource == null) return failure;
            try {
                resource.close();
            } catch (Exception closeFailure) {
                if (failure == null) return new GlException("fixture close failed", closeFailure);
                failure.addSuppressed(closeFailure);
            }
            return failure;
        }

        private static void closeQuietly(AutoCloseable resource) {
            if (resource == null) return;
            try {
                resource.close();
            } catch (Exception ignored) {
                // The primary allocation failure is reported by the caller.
            }
        }
    }

    private static Mesh horizontalQuad(float minX, float maxX, float minZ, float maxZ,
                                       float y) {
        float[] positions = {
                minX, y, minZ,
                maxX, y, minZ,
                maxX, y, maxZ,
                minX, y, maxZ
        };
        return positionMesh(positions, new int[]{0, 1, 2, 0, 2, 3});
    }

    private static Mesh boxMesh(float halfX, float halfY, float halfZ) {
        float[] positions = {
                -halfX, -halfY, -halfZ, halfX, -halfY, -halfZ,
                halfX, halfY, -halfZ, -halfX, halfY, -halfZ,
                -halfX, -halfY, halfZ, halfX, -halfY, halfZ,
                halfX, halfY, halfZ, -halfX, halfY, halfZ
        };
        int[] indices = {
                0, 1, 2, 0, 2, 3,
                4, 6, 5, 4, 7, 6,
                0, 4, 5, 0, 5, 1,
                3, 2, 6, 3, 6, 7,
                1, 5, 6, 1, 6, 2,
                0, 3, 7, 0, 7, 4
        };
        return positionMesh(positions, indices);
    }

    private static Mesh positionMesh(float[] positions, int[] indices) {
        VertexAttribute position = VertexAttribute.builder()
                .index(0).size(3).offsetBytes(0L)
                .semantic(VertexSemantic.POSITION).build();
        return Mesh.builder()
                .vertices(positions, 3 * Float.BYTES, position)
                .indices(indices)
                .build();
    }
}
