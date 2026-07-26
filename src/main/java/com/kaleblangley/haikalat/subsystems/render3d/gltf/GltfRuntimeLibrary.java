package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;

/** 应用级 glTF runtime 共享资源；创建和关闭必须发生在当前 OpenGL context 上。 */
public final class GltfRuntimeLibrary implements AutoCloseable {
    private final ShaderProgram shader;
    private final PbrFallbackTextures fallbacks;
    private final Sampler defaultSampler;
    private int activeAssets;
    private boolean closed;

    private GltfRuntimeLibrary() {
        ShaderProgram createdShader = ShaderProgram.fromResource(GltfRuntimeLibrary.class,
                "/shaders/render3d/pbr/pbr-forward.vert", "/shaders/render3d/pbr/pbr-forward.frag");
        PbrFallbackTextures createdFallbacks;
        Sampler createdSampler;
        try (CloseStack rollback = new CloseStack()) {
            rollback.own(createdShader);
            createdFallbacks = rollback.own(new PbrFallbackTextures());
            createdSampler = rollback.own(Sampler.linearRepeat());
            rollback.releaseOwnership();
        }
        shader = createdShader;
        fallbacks = createdFallbacks;
        defaultSampler = createdSampler;
    }

    public static GltfRuntimeLibrary create() { return new GltfRuntimeLibrary(); }

    ShaderProgram shader() { ensureOpen(); return shader; }
    PbrFallbackTextures fallbacks() { ensureOpen(); return fallbacks; }
    Sampler defaultSampler() { ensureOpen(); return defaultSampler; }

    void retainAsset() { ensureOpen(); activeAssets++; }
    void releaseAsset() {
        if (activeAssets <= 0) throw new IllegalStateException("glTF runtime active-asset count underflow");
        activeAssets--;
    }

    public int activeAssetCount() { return activeAssets; }
    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return;
        if (activeAssets != 0) throw new IllegalStateException(
                "cannot close glTF runtime library while " + activeAssets + " scene assets are active");
        closed = true;
        CloseStack resources = new CloseStack();
        resources.own(shader);
        resources.own(fallbacks);
        resources.own(defaultSampler);
        resources.close();
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("glTF runtime library is closed"); }
}
