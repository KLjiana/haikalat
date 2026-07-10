package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.kaleblangley.haikalat.backend.framebuffer.RenderTargetManager;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.buffer.TripleBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.runtime.FrameDriver;
import com.kaleblangley.haikalat.runtime.RenderSettings;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;

/** Minimal proof of the engine window, command, material, mesh, target, and instancing APIs. */
public final class MinimalDemo {
    private static final String SCENE_TARGET = "MinimalScene";

    private MinimalDemo() {
    }

    public static void main(String[] args) {
        RenderSettings settings = RenderSettings.builder().vsync(true).build();
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(800, 600)
                .title("MinimalDemo")
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(settings.vsync());
            window.show();
            run(window, settings);
        }
    }

    private static void run(GlfwWindow window, RenderSettings settings) {
        FrameDriver frameDriver = new FrameDriver(settings);
        RenderTargetManager targets = new RenderTargetManager();

        ShaderProgram colorShader = ShaderProgram.fromResource(MinimalDemo.class,
                "/demo/color_mvp.vert", "/demo/color_unlit.frag");
        ShaderProgram texShader = ShaderProgram.fromResource(MinimalDemo.class,
                "/demo/textured_mvp.vert", "/demo/textured_unlit.frag");
        ShaderProgram instShader = ShaderProgram.fromResource(MinimalDemo.class,
                "/demo/instanced_projview.vert", "/demo/instanced_projview.frag");
        Texture2D wallTexture = Texture2D.fromResource(MinimalDemo.class, "/wall.png", false);

        Mesh triangle = Mesh.from(BuiltinMeshData.coloredTriangle("minimal-triangle"));
        Mesh quad = Mesh.from(BuiltinMeshData.coloredQuad("minimal-instanced-quad"));
        Mesh texturedQuad = Mesh.from(BuiltinMeshData.texturedQuad("minimal-textured-quad"));
        InstancedMeshBatch instancedBatch = InstancedMeshBatch.of(quad, 16, 3);

        Material colorMaterial = Material.builder(colorShader).blendMode(BlendMode.OPAQUE).build();
        Material texturedMaterial = Material.builder(texShader)
                .texture("uTexture", wallTexture)
                .setVec3("uTint", new Vector3f(1.0f))
                .blendMode(BlendMode.OPAQUE)
                .build();
        TripleBuffer<List<Matrix4f>> transforms = new TripleBuffer<>(ArrayList::new);

        targets.create(SCENE_TARGET, FramebufferDescriptor.singleColorDepthRenderbuffer(
                window.width(), window.height()));

        Matrix4f projection = new Matrix4f();
        Matrix4f view = new Matrix4f().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0);
        Matrix4f projectionView = new Matrix4f();
        Matrix4f model = new Matrix4f();
        Matrix4f mvp = new Matrix4f();

        int frame = 0;
        try {
            while (!window.shouldClose()) {
                if (window.isKeyDown(GLFW_KEY_ESCAPE)) {
                    window.requestClose();
                }
                if (window.consumeResize()) {
                    targets.resize(window.width(), window.height());
                }
                Framebuffer sceneTarget = targets.get(SCENE_TARGET);

                projection.identity().perspective((float) Math.toRadians(45.0),
                        window.width() / (float) Math.max(1, window.height()), 0.1f, 100.0f);
                projection.mul(view, projectionView);
                updateInstances(transforms, frame);

                var commands = frameDriver.device().createCommandBuffer();
                commands.bindFramebuffer(sceneTarget)
                        .viewport(0, 0, sceneTarget.width(), sceneTarget.height())
                        .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                        .clear(true, true);

                colorMaterial.bind(commands);
                projectionView.mul(model.identity().translation(-1.0f, 0.5f, 0.0f)
                        .rotateZ(frame * 0.03f), mvp);
                commands.setUniformMat4(colorShader, "uMvp", mvp)
                        .bindMesh(triangle).drawMesh(triangle);

                texturedMaterial.bind(commands);
                projectionView.mul(model.identity().translation(1.0f, 0.5f, 0.0f)
                        .rotateZ(-frame * 0.02f), mvp);
                commands.setUniformMat4(texShader, "uMvp", mvp)
                        .bindMesh(texturedQuad).drawMesh(texturedQuad);

                commands.bindShader(instShader)
                        .setUniformMat4(instShader, "uProjView", projectionView)
                        .drawInstancedBatch(instancedBatch, transforms.read())
                        .bindFramebuffer(GL_FRAMEBUFFER, 0)
                        .viewport(0, 0, window.width(), window.height())
                        .blitToDefault(sceneTarget, window.width(), window.height());

                frameDriver.beginFrame();
                frameDriver.submit(commands);
                frameDriver.endFrame();

                if ((frame % 60) == 0) {
                    window.setTitle(String.format("Minimal | FPS %.1f | objs=2+inst",
                            frameDriver.statistics().averageFps()));
                }
                GlDebug.checkError("MinimalDemo");
                window.swapBuffers();
                window.pollEvents();
                frame++;
            }
        } finally {
            frameDriver.close();
            instancedBatch.close();
            targets.close();
            texturedQuad.close();
            quad.close();
            triangle.close();
            wallTexture.close();
            instShader.close();
            texShader.close();
            colorShader.close();
        }
    }

    private static void updateInstances(TripleBuffer<List<Matrix4f>> transforms, int frame) {
        List<Matrix4f> writes = transforms.write();
        writes.clear();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                writes.add(new Matrix4f()
                        .translation(-1.4f + column * 0.7f, -1.4f + row * 0.7f, 0.0f)
                        .rotateZ(frame * 0.04f + (row + column) * 0.3f)
                        .scale(0.3f));
            }
        }
        transforms.flip();
    }
}
