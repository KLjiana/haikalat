package com.kaleblangley.haikalat.gl.mesh;

import com.kaleblangley.haikalat.gl.GlException;
import com.kaleblangley.haikalat.gl.buffer.GlBuffer;
import com.kaleblangley.haikalat.gl.buffer.InstanceBufferRing;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;

public final class InstancedMeshBatch implements AutoCloseable {
    private final Mesh mesh;
    private final VertexArray vertexArray;
    private final InstanceBufferRing instanceBuffers;
    private final VertexLayout instanceLayout;
    private final List<Matrix4f> transforms = new ArrayList<>();
    private boolean closed;

    private InstancedMeshBatch(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.instanceLayout = VertexLayout.instanceMatrix(baseAttributeLocation);
        this.vertexArray = new VertexArray();
        this.instanceBuffers = new InstanceBufferRing(maxInstances);

        vertexArray.bind();
        setupMeshAttributes(mesh);
        instanceBuffers.activeBuffer().bind();
        instanceLayout.apply();
        vertexArray.unbind();
    }

    private static void setupMeshAttributes(Mesh mesh) {
        GlBuffer[] allBufs = mesh.allVertexBuffers();
        List<VertexAttribute> attrs = mesh.vertexLayout().attributes();
        if (!mesh.isInterleaved()) {
            for (int i = 0; i < attrs.size(); i++) {
                VertexAttribute attr = attrs.get(i);
                allBufs[i].bind();
                int stride = attr.size() * Float.BYTES;
                glVertexAttribPointer(attr.index(), attr.size(), attr.type(), attr.normalized(), stride, 0);
                glEnableVertexAttribArray(attr.index());
            }
        } else {
            allBufs[0].bind();
            mesh.vertexLayout().apply();
        }
        if (mesh.indexBuffer() != null) {
            mesh.indexBuffer().bind();
        }
    }

    public static InstancedMeshBatch of(Mesh mesh, int maxInstances, int baseAttributeLocation) {
        return new InstancedMeshBatch(mesh, maxInstances, baseAttributeLocation);
    }

    public InstancedMeshBatch beginFrame() {
        ensureOpen();
        transforms.clear();
        instanceBuffers.beginFrame();
        return this;
    }

    public InstancedMeshBatch submit(Matrix4f transform) {
        ensureOpen();
        transforms.add(new Matrix4f(Objects.requireNonNull(transform, "transform")));
        return this;
    }

    public InstancedMeshBatch submitAll(Iterable<Matrix4f> batch) {
        ensureOpen();
        for (Matrix4f transform : batch) {
            submit(transform);
        }
        return this;
    }

    public int flush() {
        ensureOpen();
        if (transforms.isEmpty()) {
            return 0;
        }
        instanceBuffers.upload(transforms);
        vertexArray.bind();
        instanceBuffers.bindAttributes(baseAttributeLocation());
        mesh.drawInstancedBound(transforms.size());
        instanceBuffers.finishFrame();
        int drawn = transforms.size();
        transforms.clear();
        return drawn;
    }

    public boolean isPersistent() {
        return instanceBuffers.isPersistent();
    }

    private int baseAttributeLocation() {
        return instanceLayout.attributes().get(0).index();
    }

    public VertexArray vertexArray() {
        return vertexArray;
    }

    public Mesh mesh() {
        return mesh;
    }

    public int pendingInstances() {
        return transforms.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        instanceBuffers.close();
        vertexArray.close();
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("InstancedMeshBatch is closed");
        }
    }
}
