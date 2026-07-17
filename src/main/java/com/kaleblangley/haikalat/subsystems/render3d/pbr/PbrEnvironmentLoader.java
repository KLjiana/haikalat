package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.device.RenderDevice;

import java.util.Objects;

/** 在当前 render thread/context 中解码 HDR 并执行一次 GPU IBL 预计算。 */
public final class PbrEnvironmentLoader {
    private PbrEnvironmentLoader() {
    }

    /**
     * 使用将继续负责该 context 绘制的同一设备执行预计算。
     * 预计算会在清理临时 GL 资源后失效该设备的状态缓存。
     */
    public static PbrEnvironment load(RenderDevice device, Class<?> anchor, String hdrResource,
                                      PbrEnvironmentSettings settings) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(hdrResource, "hdrResource");
        Objects.requireNonNull(settings, "settings");
        Texture2D source = Texture2D.fromHdrResource(anchor, hdrResource, false);
        Throwable primaryFailure = null;
        try {
            return EnvironmentPreprocessor.preprocess(device, source, settings);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                source.close();
            } catch (RuntimeException cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }
}
