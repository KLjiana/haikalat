package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.core.presentation.OwnedPresentationTarget;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.*;
import com.kaleblangley.haikalat.subsystems.render3d.ScreenQuad;
import com.kaleblangley.haikalat.subsystems.ui.UiSystem;
import com.kaleblangley.haikalat.subsystems.ui.render.UiAttachmentOptions;
import com.kaleblangley.haikalat.subsystems.ui.render.UiBackdropSource;
import com.kaleblangley.haikalat.subsystems.ui.widget.Button;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.opengl.GL11.*;

/** Standalone Haikalat VLS demo; no Minecraft runtime or browser is required. */
public final class VlsDemo {
    private VlsDemo() { }

    public static void main(String[] args) throws Exception {
        boolean hidden = false, verify = false, reduced = false, blurEnabled = true;
        int frames = 0, width = 1440, height = 900, environment = 0;
        Path capture = null;
        for (String arg : args) {
            if (arg.equals("--hidden")) hidden = true;
            else if (arg.equals("--verify")) { hidden = true; verify = true; frames = 100; }
            else if (arg.equals("--reduced-motion")) reduced = true;
            else if (arg.equals("--no-blur")) blurEnabled = false;
            else if (arg.startsWith("--frames=")) frames = positive(arg.substring(9));
            else if (arg.startsWith("--environment=")) environment = Integer.parseInt(arg.substring(14));
            else if (arg.startsWith("--capture=")) capture = Path.of(arg.substring(10));
            else if (arg.startsWith("--size=")) {
                String[] size = arg.substring(7).split("x");
                width = positive(size[0]); height = positive(size[1]);
            } else throw new IllegalArgumentException("Unknown option: " + arg);
        }
        if (environment < 0 || environment > 3) throw new IllegalArgumentException("environment: 0..3");
        if (width < 1100 || height < 720) throw new IllegalArgumentException("Minimum demo size: 1100x720");
        if (hidden && frames == 0) frames = 60;
        if (verify && frames < 100) throw new IllegalArgumentException("Verification requires at least 100 frames");
        if (capture != null && frames == 0) frames = 60;

        try (GlfwWindow window = new GlfwWindow.Builder().dimensions(width, height)
                .title("林境远征 · VLS 2.0 · Haikalat").visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL).build()) {
            window.bindContext(); GL.createCapabilities(); GlDebug.enableDebugCallback();
            org.lwjgl.glfw.GLFW.glfwSetWindowSizeLimits(window.handle(), 1100, 720, -1, -1);
            window.setVsync(!hidden);
            try (FrameDriver driver = new FrameDriver(RenderSettings.builder().vsync(!hidden).build());
                 ShaderProgram shader = ShaderProgram.fromResource(VlsDemo.class,
                         "/shaders/demo/vls-world.vert", "/shaders/demo/vls-world.frag");
                 ShaderProgram presentShader = ShaderProgram.fromResource(VlsDemo.class,
                         "/shaders/demo/vls-world.vert", "/shaders/demo/vls-present.frag");
                 Sampler sampler = Sampler.create(new Sampler.Descriptor(GL_LINEAR, GL_LINEAR,
                         org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE));
                 ScreenQuad quad = new ScreenQuad();
                 Surface surface = new Surface();
                 RenderGraph graph = new RenderGraph(window.width(), window.height());
                 UiSystem ui = UiSystem.create(window, VlsDemoScene.config())) {
                VlsDemoScene scene = new VlsDemoScene(ui, environment, reduced, width, height);
                scene.setBlur(blurEnabled);
                graph.addPass("VlsWorld").createColor("VlsWorldColor", RenderFormat.RGBA16F)
                        .relativeSize(.5f).noClear().execute((resources, commands) -> commands
                        .enableDepthTest(false).enableCullFace(false).enableBlend(false)
                        .enableFramebufferSrgb(false).bindShader(shader)
                        .setUniformVec2(shader, "uResolution", resources.currentTarget().width(), resources.currentTarget().height())
                        .setUniformFloat(shader, "uTime", scene.time())
                        .setUniformInt(shader, "uEnvironment", scene.environment())
                        .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6));
                // World presentation (veil/tint) produces explicit display-stage linear color.
                // Filtering and subtree composition belong to the public UI compositor.
                graph.addPass("VlsDisplayColor").createColor("VlsPreUiColor", RenderFormat.RGBA16F)
                        .relativeSize(.5f).dependsOn("VlsWorld").noClear().execute((r, c) -> c
                        .enableBlend(false).enableDepthTest(false).enableFramebufferSrgb(false)
                        .bindShader(presentShader).bindTexture(0, r.colorAttachment("VlsWorldColor")).bindSampler(0, sampler)
                        .setUniformInt(presentShader, "uSource", 0)
                        .setUniformInt(presentShader, "uEnvironment", scene.environment())
                        .setUniformVec2(presentShader, "uViewport", r.currentTarget().width(), r.currentTarget().height())
                        .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6));
                graph.addPass("VlsBackdrop").writeToBackbuffer().dependsOn("VlsDisplayColor").noClear().execute((r, c) -> c
                        .enableFramebufferSrgb(true).blitFramebuffer(r.framebufferOfPass("VlsDisplayColor").id(),
                                r.presentationTarget().drawFramebufferId(),r.framebufferOfPass("VlsDisplayColor").width(),
                                r.framebufferOfPass("VlsDisplayColor").height(),r.presentationTarget().width(),r.presentationTarget().height(),
                                GL_COLOR_BUFFER_BIT,GL_LINEAR));
                ui.attachTo(graph, "VlsBackdrop", UiAttachmentOptions.builder().enableCompositor(true)
                        .backdropSource(UiBackdropSource.available("VlsPreUiColor"))
                        .layers(java.util.List.of(scene.backdropDescription())).build()); graph.compile();
                ui.update(window.inputSnapshot(), 0); surface.frame(driver, graph);
                if (!hidden) window.show();
                FrameClock clock = new FrameClock();
                WindowInputCollector scripted = new WindowInputCollector();
                scripted.windowSize(width, height); scripted.framebufferSize(window.width(), window.height());
                scripted.contentScale(1, 1); scripted.focused(true); scripted.cursorInside(true);
                scripted.snapshot(); // The prewarm consumed sequence 1 from the native collector.
                int count = 0;
                while (!window.shouldClose() && (frames == 0 || count < frames)) {
                    long start = System.nanoTime();
                    if (verify && count == 80) window.resize(1100, 720);
                    window.pollEvents();
                    if (window.consumeResize()) graph.resize(window.width(), window.height());
                    WindowInputSnapshot input = window.inputSnapshot();
                    if (input.keyPressed(Key.ESCAPE)) window.requestClose();
                    float delta = hidden ? 1f / 60 : clock.tick().deltaSeconds();
                    if (verify) {
                        scripted.windowSize(input.windowWidth(), input.windowHeight());
                        scripted.framebufferSize(input.framebufferWidth(), input.framebufferHeight());
                        scripted.contentScale(input.contentScaleX(), input.contentScaleY());
                        script(scene, scripted, count); input = scripted.snapshot();
                    }
                    scene.update(delta); ui.update(input, delta); surface.frame(driver, graph);
                    if (verify) {
                        verifyState(scene, count);
                        if(ui.compositorRuntimeStatistics().blurDraws()!=(scene.blurEnabled()?2:0))
                            throw new IllegalStateException("VLS blur control did not reach the public compositor");
                        if(ui.compositorDiagnostics().activeLayers()!=1||!ui.compositorDiagnostics().fallbacks().isEmpty())
                            throw new IllegalStateException("VLS public backdrop layer unexpectedly degraded");
                        if (count == 83) {
                            if (input.windowWidth() != 1100 || input.windowHeight() != 720)
                                throw new IllegalStateException("Native resize did not reach 1100x720");
                            scene.verifyLayout();
                        }
                    }
                    if ((capture != null || verify) && count == frames - 1) capture(window.width(), window.height(), capture);
                    GlDebug.checkError("VlsDemo frame " + count);
                    driver.present(window::swapBuffers);
                    if (!hidden) {
                        long remaining = 16_666_667 - (System.nanoTime() - start);
                        if (remaining > 0) LockSupport.parkNanos(remaining);
                    }
                    count++;
                }
                System.out.printf("VlsDemo | frames=%d | framebuffer=%dx%d | draws=%d | quads=%d | interactionProof=%s%n",
                        count, window.width(), window.height(), ui.statistics().drawCalls(), ui.statistics().quads(), verify);
            }
        }
    }

    private static int positive(String text) {
        int value = Integer.parseInt(text);
        if (value < 1) throw new IllegalArgumentException("Expected a positive number");
        return value;
    }

    /** Explicit sRGB target: the generic graph default target describes RGBA8. */
    private static final class Surface implements AutoCloseable {
        private OwnedPresentationTarget owned;
        void frame(FrameDriver driver, RenderGraph graph) {
            int w = graph.width(), h = graph.height();
            if (owned == null || owned.target().width() != w || owned.target().height() != h) {
                if (owned != null) owned.close();
                owned = OwnedPresentationTarget.create(w, h, RenderFormat.SRGB8_ALPHA8, false, 0);
            }
            driver.beginFrame();
            try {
                graph.execute(driver.device(), owned.target());
                driver.recordGraph(graph);
                driver.submit(driver.device().createCommandBuffer().enableFramebufferSrgb(false)
                        .blitFramebuffer(owned.target().drawFramebufferId(), 0, w, h, w, h));
                driver.endFrame();
            } catch (RuntimeException | Error failure) {
                driver.failFrame(graph, failure); throw failure;
            }
        }
        @Override public void close() { if (owned != null) owned.close(); }
    }

    /** Goes through WindowInputCollector -> UiSystem -> hit testing/capture -> Button listeners. */
    private static void script(VlsDemoScene scene, WindowInputCollector input, int frame) {
        if (frame == 90) input.pressKey(Key.TAB, KeyModifiers.NONE);
        if (frame == 91) input.releaseKey(Key.TAB, KeyModifiers.NONE);
        if (frame == 92) input.pressKey(Key.ENTER, KeyModifiers.NONE);
        if (frame == 93) input.releaseKey(Key.ENTER, KeyModifiers.NONE);
        String id = switch (frame) {
            case 2, 3, 8, 9 -> "blur";
            case 4, 5 -> "task2";
            case 10, 11 -> "task1";
            case 16, 17, 22, 23 -> "action";
            case 28, 29 -> "task2";
            case 34, 35, 46, 47 -> "page";
            case 40, 41 -> "item1";
            case 52, 53, 58, 59, 64, 65, 70, 71 -> "environment";
            case 76, 77 -> "motion";
            case 84, 85 -> "task1";
            default -> null;
        };
        if (id == null) return;
        Button button = scene.controls.get(id);
        var bounds = button.layoutBox();
        input.cursorPosition(bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2);
        if ((frame & 1) == 0) input.pressMouse(MouseButton.LEFT, KeyModifiers.NONE);
        else input.releaseMouse(MouseButton.LEFT, KeyModifiers.NONE);
    }

    private static void verifyState(VlsDemoScene scene, int frame) {
        if (frame == 7 && scene.blurEnabled()) throw new IllegalStateException("Blur switch did not disable Gaussian output");
        if (frame == 13 && !scene.blurEnabled()) throw new IllegalStateException("Blur switch did not restore Gaussian output");
        if (frame == 7 && scene.controls.get("action").enabled()) throw new IllegalStateException("Locked task was actionable");
        if (frame == 25 && !scene.completed()) throw new IllegalStateException("Pointer path did not complete task");
        if (frame == 31 && !scene.controls.get("action").enabled()) throw new IllegalStateException("Next task did not unlock");
        if (frame == 43 && scene.controls.get("action").enabled()) throw new IllegalStateException("Inventory detail not shown");
        if (frame == 79 && !scene.reducedMotion()) throw new IllegalStateException("Reduced motion toggle failed");
        if (frame == 95 && !scene.controls.get("action").enabled()) throw new IllegalStateException("Tab/Enter did not select unlocked next task");
    }

    private static void capture(int width, int height, Path path) throws Exception {
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadBuffer(GL_BACK); glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = (y * width + x) * 4;
            image.setRGB(x, height - y - 1, (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | pixels.get(i + 2) & 255);
            if (x % 16 == 0 && y % 16 == 0) colors.add(image.getRGB(x, height - y - 1));
        }
        if (colors.size() < 80) throw new IllegalStateException("World/UI framebuffer lacks expected visual content: " + colors.size());
        if (path == null) return;
        Path output = path.toAbsolutePath(); Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) throw new IllegalStateException("PNG writer unavailable");
        System.out.println("Captured " + output);
    }
}
