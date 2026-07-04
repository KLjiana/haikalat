package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.VertexAttribute;
import com.kaleblangley.haikalat.core.mesh.VertexLayout;
import com.kaleblangley.haikalat.core.mesh.VertexPacking;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.render3d.InstancedRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL33.*;

public final class LearnOpenGlDemo {

    private static final VertexLayout POS_COLOR_LAYOUT = VertexLayout.interleaved(6 * Float.BYTES,
            VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
            VertexAttribute.builder().index(1).size(3).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build());

    public static void main(String[] args) {
        demoVertexPacking();

        RenderSettings settings = RenderSettings.builder().debugErrors(true).build();
        AppWindow window = new AppWindow(1280, 720, "LearnOpenGL Demo", settings.vsync());
        window.show();

        FrameDriver renderLoop = new FrameDriver(settings);

        ShaderProgram colorShader = ShaderProgram.fromResource(LearnOpenGlDemo.class,
                "/color.vert", "/color.frag");
        ShaderProgram texturedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class,
                "/textured.vert", "/textured.frag");
        ShaderProgram instancedShader = ShaderProgram.fromResource(LearnOpenGlDemo.class,
                "/instanced.vert", "/instanced.frag");

        Texture2D wallTex = Texture2D.fromResource(LearnOpenGlDemo.class, "/wall.png", false);
        Texture2D faceTex = Texture2D.fromResource(LearnOpenGlDemo.class, "/awesomeface.png", true);

        Material colorMat = Material.builder(colorShader).blendMode(BlendMode.OPAQUE).build();
        Material wallMat = Material.builder(texturedShader)
                .texture("uTexture", wallTex)
                .setVec3("uTint", new Vector3f(1, 1, 1))
                .blendMode(BlendMode.OPAQUE).build();
        Material faceMat = Material.builder(texturedShader)
                .texture("uTexture", faceTex)
                .setVec3("uTint", new Vector3f(1, 1, 1))
                .blendMode(BlendMode.ALPHA).build();

        Mesh triangle = Mesh.builder().layout(POS_COLOR_LAYOUT)
                .attribute(0, new float[]{-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f}, 3)
                .attribute(1, new float[]{1.0f, 0.3f, 0.2f, 0.2f, 1.0f, 0.3f, 0.2f, 0.3f, 1.0f}, 3).build();

        Mesh coloredQuad = Mesh.builder().layout(POS_COLOR_LAYOUT)
                .attribute(0, new float[]{
                        -0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f,
                        -0.5f, -0.5f, 0.0f, 0.5f, 0.5f, 0.0f, -0.5f, 0.5f, 0.0f}, 3)
                .attribute(1, new float[]{
                        1.0f, 0.8f, 0.2f, 0.2f, 0.8f, 1.0f, 0.8f, 0.2f, 1.0f,
                        1.0f, 0.8f, 0.2f, 0.8f, 0.2f, 1.0f, 0.2f, 1.0f, 0.8f}, 3).build();

        Mesh texQuad = Mesh.builder()
                .vertices(new float[]{
                        -0.5f, -0.5f, 0.0f, 0.0f, 0.0f,
                        0.5f, -0.5f, 0.0f, 1.0f, 0.0f,
                        0.5f, 0.5f, 0.0f, 1.0f, 1.0f,
                        -0.5f, 0.5f, 0.0f, 0.0f, 1.0f}, 5 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).type(GL_FLOAT).offsetBytes(0).build(),
                        VertexAttribute.builder().index(1).size(2).type(GL_FLOAT).offsetBytes(3L * Float.BYTES).build())
                .indices(new int[]{0, 1, 2, 0, 2, 3}).build();

        List<SceneObject> scene = new ArrayList<>();
        scene.add(new SceneObject(triangle, colorMat,
                (m, f) -> m.translation(-1.5f, 0.5f, -2.5f).rotateZ(f * 0.03f)));
        scene.add(new SceneObject(texQuad, wallMat,
                (m, f) -> m.translation(1.0f, 0.5f, -2.5f).rotateZ(-f * 0.02f)));
        scene.add(new SceneObject(coloredQuad, colorMat,
                (m, f) -> m.translation(-1.5f, -1.0f, -3.0f).scale(0.6f)));
        scene.add(new SceneObject(texQuad, faceMat,
                (m, f) -> m.translation(1.5f, (float) Math.sin(f * 0.04f) * 0.5f - 1.0f, -3.0f).scale(0.5f)));

        InstancedMeshBatch instBatch = InstancedMeshBatch.of(coloredQuad, 16, 2);
        InstancedRenderer instanced = new InstancedRenderer(instBatch, instancedShader);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                final int row = r, col = c;
                instanced.addInstance(f -> new Matrix4f()
                        .translation(-1.4f + col * 0.7f, -1.4f + row * 0.7f, 0)
                        .rotateZ(f * 0.04f + (row + col) * 0.3f)
                        .scale(0.3f));
            }
        }

        RenderPipeline pipeline = new RenderPipeline(window, window.camera(), scene, instanced);
        pipeline.build();

        AtomicInteger frame = new AtomicInteger(0);
        window.run((w, dt) -> {
            if (w.consumeResize()) pipeline.resize(w.width(), w.height());

            instanced.beginFrame(frame.get());
            renderLoop.frame(pipeline.graph());

            if ((frame.get() % 60) == 0) {
                double fps = renderLoop.statistics().averageFps();
                w.setTitle(String.format("LearnOpenGL | FPS %.1f | inst %d | %s",
                        fps, instanced.drawnCount(),
                        instanced.supportsPersistent() ? "pers" : "glSub"));
            }

            GlDebug.checkError("LearnOpenGlDemo.frame");
            frame.incrementAndGet();
        });

        pipeline.close();
        instanced.close();
        coloredQuad.close();
        texQuad.close();
        triangle.close();
        faceTex.close();
        wallTex.close();
        instancedShader.close();
        texturedShader.close();
        colorShader.close();
        window.close();
    }

    private static void demoVertexPacking() {
        float[] u = VertexPacking.unpackOctNormal(
                VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f));
        System.out.printf("[VertexPacking] packed=0x%08X unpacked=(%.3f,%.3f,%.3f)%n",
                VertexPacking.packOctNormal(0.5f, 0.5f, 0.7071f), u[0], u[1], u[2]);
    }
}
