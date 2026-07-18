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
        shader = ShaderProgram.fromResource(GltfRuntimeLibrary.class,
                "/render3d/pbr/pbr_forward.vert", "/render3d/pbr/pbr_forward.frag");
        PbrFallbackTextures createdFallbacks = null;
        Sampler createdSampler = null;
        try {
            createdFallbacks = new PbrFallbackTextures();
            createdSampler = Sampler.linearRepeat();
        } catch (RuntimeException failure) {
            if (createdSampler != null) {
                try { createdSampler.close(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (createdFallbacks != null) {
                try { createdFallbacks.close(); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { shader.close(); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
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
        RuntimeException failure = null;
        try { defaultSampler.close(); } catch (RuntimeException error) { failure = error; }
        try { fallbacks.close(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        try { shader.close(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("glTF runtime library is closed"); }
}
