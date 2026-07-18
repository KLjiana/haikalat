package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsJsonExporter;
import com.kaleblangley.haikalat.runtime.diagnostics.DiagnosticsLevel;
import com.kaleblangley.haikalat.runtime.diagnostics.FrozenDiagnostics;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.file.Path;

/** 真实触发 pass callback 与命令执行失败，并验证下一帧 query/debug-group 可恢复。 */
public final class DiagnosticsFailureIntegration {
    private static final int FRAME_COUNT = 6;
    private static final int CALLBACK_FAILURE_FRAME = 1;
    private static final int COMMAND_FAILURE_FRAME = 3;

    private DiagnosticsFailureIntegration() {
    }

    public static void main(String[] arguments) throws IOException {
        Path output = arguments.length == 0
                ? Path.of("build", "diagnostics", "failure.json")
                : Path.of(arguments[0]);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 64).title("Diagnostics failure integration")
                .visible(false).cursorMode(GlfwWindow.CursorMode.NORMAL).build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(false);
            int[] recordingFrame = {-1};
            FrozenDiagnostics capture;
            try (FrameDriver driver = new FrameDriver(
                    RenderSettings.builder().vsync(false).build(), DiagnosticsLevel.DETAILED);
                 RenderGraph graph = graph(recordingFrame)) {
                for (int frame = 0; frame < FRAME_COUNT; frame++) {
                    recordingFrame[0] = frame;
                    driver.beginFrame();
                    try {
                        graph.execute(driver.device());
                        GlDebug.assertNoError("DiagnosticsFailureIntegration.execute " + frame);
                        driver.recordGraph(graph);
                        driver.endFrame();
                    } catch (CallbackFailure | CommandFailure expected) {
                        driver.failFrame(graph, expected);
                        GlDebug.assertNoError("DiagnosticsFailureIntegration.recover " + frame);
                    }
                    if (GlDebug.debugGroupDepth() != 0) {
                        throw new IllegalStateException("debug group leaked after frame " + frame);
                    }
                    driver.present(window::swapBuffers);
                    window.pollEvents();
                }
                capture = driver.diagnostics().freeze();
                verify(capture);
                DiagnosticsJsonExporter.export(capture, output);
            }
            if (!GlDebug.resources().liveResources().isEmpty()) {
                throw new IllegalStateException("diagnostics failure integration leaked resources");
            }
        }
    }

    private static RenderGraph graph(int[] recordingFrame) {
        RenderGraph graph = new RenderGraph(64, 64);
        graph.addPass("FailureProbe")
                .writeToBackbuffer()
                .noClear()
                .execute((resources, commands) -> {
                    int frame = recordingFrame[0];
                    if (frame == CALLBACK_FAILURE_FRAME) throw new CallbackFailure();
                    commands.clearColor(0.02f, 0.03f, 0.05f, 1.0f).clear(true, true);
                    if (frame == COMMAND_FAILURE_FRAME) {
                        commands.custom(() -> { throw new CommandFailure(); });
                    }
                });
        graph.compile();
        return graph;
    }

    private static void verify(FrozenDiagnostics capture) {
        if (capture.history().size() != FRAME_COUNT) {
            throw new IllegalStateException("expected " + FRAME_COUNT + " diagnostic frames");
        }
        for (int index = 0; index < capture.history().size(); index++) {
            var frame = capture.history().get(index);
            if (frame.frameSequence() != index) {
                throw new IllegalStateException("diagnostic frame identity mismatch at " + index);
            }
            boolean expectedFailure = index == CALLBACK_FAILURE_FRAME
                    || index == COMMAND_FAILURE_FRAME;
            if (frame.complete() == expectedFailure) {
                throw new IllegalStateException("unexpected completion state at " + index);
            }
        }
        if (!capture.history().get(FRAME_COUNT - 1).complete()) {
            throw new IllegalStateException("rendering did not recover after command failure");
        }
    }

    private static final class CallbackFailure extends RuntimeException {
    }

    private static final class CommandFailure extends RuntimeException {
    }
}
