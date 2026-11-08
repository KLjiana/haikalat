package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.shader.ShaderStage;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static com.kaleblangley.haikalat.subsystems.render3d.SceneBufferChannel.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class SceneSurfaceResolveGlTest {
    @Test
    void coverageAndDepthTiesSelectOneSampleForAllResolvedChannels() {
        int width = 33, height = 17;
        var layout = SceneSurfaceLayout.plan(4, SceneBufferRequirements.none(),
                SceneBufferRequirements.gtao(true), SceneSurfaceLayout.ReactiveMode.NONE);
        try (var window = GtaoModularityGlTest.window()) {
            window.bindContext();
            GL.createCapabilities();
            try (var producer = ShaderProgram.builder()
                         .resource(SceneSurfaceResolveGlTest.class, ShaderStage.VERTEX, "/shaders/postprocess/screen-quad.vert")
                         .stage(ShaderStage.FRAGMENT, SAMPLE_PATTERN).link();
                 var quad = new ScreenQuad();
                 var resolve = new SceneSurfaceResolvePass(layout);
                 var graph = new RenderGraph(width, height)) {
                var inputs = new SceneSurfaceInputs(1, graph, layout);
                var raw = graph.addPass(layout.raw(DEPTH).producer());
                layout.configureRaster(raw);
                raw.execute((res, cmd) -> {
                    cmd.enableBlend(false).enableCullFace(false).enableDepthTest(true).depthMask(true)
                            .bindShader(producer).bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6);
                    inputs.frame().rasterProduced(res);
                });
                var pass = graph.addPass(layout.single(DEPTH).producer());
                layout.configureResolve(pass);
                pass.execute((res, cmd) -> {
                    resolve.record(cmd, res, inputs);
                    inputs.frame().resolveProduced(res);
                });
                graph.compile();
                inputs.validate();
                Scene scene = new Scene(new Camera());
                inputs.beginFrame(RenderFrameContext.capture(scene, scene.camera(), width, height, 0, 0, 0, 0, null));
                try {
                    graph.execute(new GlRenderDevice());
                } finally {
                    inputs.endFrame();
                }
                assertRow(graph, layout, DEPTH, 1f, .2f, .1f, 1e-6f);
                assertRow(graph, layout, NORMAL, 0f, .25f, .5f, 0f);
                assertRow(graph, layout, VELOCITY, 0f, .125f, .25f, 0f);
                assertRow(graph, layout, PREVIOUS_SURFACE_DEPTH, 0f, 3f, 4f, 0f);
                assertRow(graph, layout, VALIDITY, 0f, 1f, 0f, 0f);
                GlDebug.assertNoError("shared surface nearest-sample resolve");
            }
        }
    }

    private static void assertRow(RenderGraph graph, SceneSurfaceLayout layout, SceneBufferChannel channel,
                                  float uncovered, float tied, float edge, float tolerance) {
        float[] pixels = graph.readColorAttachmentRgbaFloat(layout.single(channel).name());
        assertEquals(uncovered, pixels[0], tolerance, channel + " uncovered");
        assertEquals(tied, pixels[4], tolerance, channel + " lower sample index on depth tie");
        assertEquals(edge, pixels[8], tolerance, channel + " nearest covered sample");
    }

    private static final String SAMPLE_PATTERN = """
            #version 460 core
            layout(location=0) out vec4 normal;
            layout(location=1) out vec2 velocity;
            layout(location=2) out float previousDepth;
            layout(location=3) out float validity;
            void main() {
                int kind = int(gl_FragCoord.x) % 3;
                int s = gl_SampleID;
                if (kind == 0 || (kind == 2 && s == 0)) discard;
                const float tied[4] = float[4](0.6, 0.2, 0.2, 0.7);
                const float edge[4] = float[4](1.0, 0.7, 0.1, 0.8);
                gl_FragDepth = kind == 1 ? tied[s] : edge[s];
                normal = vec4(float(s) * 0.25, 0.0, 0.0, 1.0);
                velocity = vec2(float(s) * 0.125, 0.0);
                previousDepth = float(s) + 2.0;
                validity = s == 1 ? 1.0 : 0.0;
            }
            """;
}
