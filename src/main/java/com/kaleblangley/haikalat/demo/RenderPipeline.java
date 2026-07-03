package com.kaleblangley.haikalat.demo;

import com.kaleblangley.haikalat.gl.*;
import com.kaleblangley.haikalat.gl.command.CommandBuffer;
import com.kaleblangley.haikalat.gl.command.RenderDevice;
import com.kaleblangley.haikalat.gl.fb.Framebuffer;
import com.kaleblangley.haikalat.gl.material.Material;
import com.kaleblangley.haikalat.gl.material.ShaderProgram;
import com.kaleblangley.haikalat.gl.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.gl.mesh.Mesh;
import com.kaleblangley.haikalat.gl.render.PassResources;
import com.kaleblangley.haikalat.gl.render.RenderGraph;
import com.kaleblangley.haikalat.gl.render.RenderGraph.PassExecutor;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL30.GL_RGBA8;

public final class RenderPipeline {
    private final AppWindow window;
    private final List<SceneObject> sceneObjects;
    private final InstancedRenderer instanced;
    private RenderGraph graph;

    public RenderPipeline(AppWindow window, List<SceneObject> sceneObjects, InstancedRenderer instanced) {
        this.window = window;
        this.sceneObjects = sceneObjects;
        this.instanced = instanced;
    }

    public void build() {
        int w = window.width();
        int h = window.height();
        graph = new RenderGraph(w, h);

        graph.addPass("GeometryPass")
                .createColor("sceneColor", GL_RGBA8)
                .createDepth()
                .clearColor(0.08f, 0.10f, 0.14f, 1.0f)
                .execute(geometryExecutor());

        graph.addPass("PresentPass")
                .writeToBackbuffer()
                .noClear()
                .execute(presentExecutor());
    }

    private PassExecutor geometryExecutor() {
        return (res, cmd) -> renderScene(res, cmd);
    }

    private PassExecutor presentExecutor() {
        return (res, cmd) -> {
            Framebuffer geoFb = res.getFramebuffer("GeometryPass");
            if (geoFb != null) {
                cmd.custom(() -> geoFb.blitToDefault(geoFb.width(), geoFb.height()));
            }
        };
    }

    public void execute(RenderDevice device) {
        graph.execute(device);
    }

    public void resize(int w, int h) {
        if (graph != null) {
            graph.resize(w, h);
        }
    }

    public void close() {
        if (graph != null) {
            graph.close();
        }
    }

    private void renderScene(PassResources res, CommandBuffer cmd) {
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(45.0),
                window.width() / (float) window.height(), 0.1f, 100.0f);
        Matrix4f view = window.camera().getViewMatrix();
        Matrix4f model = new Matrix4f();

        for (SceneObject obj : sceneObjects) {
            obj.computeModel(model, instanced.frameIndex());
            obj.material().bind(cmd);
            ShaderProgram shader = obj.material().shader();
            cmd.setUniformMat4(shader, "uProjection", projection)
                    .setUniformMat4(shader, "uView", view)
                    .setUniformMat4(shader, "uModel", model)
                    .bindMesh(obj.mesh())
                    .drawMesh(obj.mesh());
        }

        instanced.render(cmd, projection, view);
    }
}
