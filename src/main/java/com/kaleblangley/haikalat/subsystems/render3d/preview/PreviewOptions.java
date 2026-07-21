package com.kaleblangley.haikalat.subsystems.render3d.preview;

import java.util.Objects;

/** 预览 shader 使用的不可变、已验证显示参数。 */
public record PreviewOptions(Mode mode, Channel channel, float exposureEv,
                             float rangeMin, float rangeMax, boolean falseColor,
                             DepthInterpretation depthInterpretation,
                             float nearPlane, float farPlane, boolean invertDepth,
                             CubeFace cubeFace, int mipLevel, Filtering filtering,
                             boolean checkerboard, int updateInterval) {
    public static final float MIN_EXPOSURE_EV = -16.0f;
    public static final float MAX_EXPOSURE_EV = 16.0f;

    public PreviewOptions {
        mode = Objects.requireNonNull(mode, "mode");
        channel = Objects.requireNonNull(channel, "channel");
        depthInterpretation = Objects.requireNonNull(depthInterpretation, "depthInterpretation");
        cubeFace = Objects.requireNonNull(cubeFace, "cubeFace");
        filtering = Objects.requireNonNull(filtering, "filtering");
        requireFinite(exposureEv, "exposureEv");
        requireFinite(rangeMin, "rangeMin");
        requireFinite(rangeMax, "rangeMax");
        requireFinite(nearPlane, "nearPlane");
        requireFinite(farPlane, "farPlane");
        exposureEv = Math.clamp(exposureEv, MIN_EXPOSURE_EV, MAX_EXPOSURE_EV);
        if (rangeMin >= rangeMax) {
            throw new IllegalArgumentException("preview range requires min < max");
        }
        if (nearPlane <= 0.0f || farPlane <= nearPlane) {
            throw new IllegalArgumentException("preview depth requires 0 < near < far");
        }
        mipLevel = Math.max(0, mipLevel);
        if (updateInterval != 1 && updateInterval != 2
                && updateInterval != 4 && updateInterval != 8) {
            throw new IllegalArgumentException("updateInterval must be 1, 2, 4 or 8");
        }
    }

    public static PreviewOptions defaults() {
        return new PreviewOptions(Mode.AUTO, Channel.RGBA, 0.0f,
                0.0f, 1.0f, false, DepthInterpretation.RAW,
                0.1f, 100.0f, false, CubeFace.POSITIVE_X, 0,
                Filtering.LINEAR, true, 1);
    }

    public PreviewOptions mode(Mode value) {
        return new PreviewOptions(value, channel, exposureEv, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions channel(Channel value) {
        return new PreviewOptions(mode, value, exposureEv, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions exposureEv(float value) {
        return new PreviewOptions(mode, channel, value, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions range(float min, float max) {
        return new PreviewOptions(mode, channel, exposureEv, min, max,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions falseColor(boolean value) {
        return new PreviewOptions(mode, channel, exposureEv, rangeMin, rangeMax,
                value, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions depth(DepthInterpretation value, float near, float far,
                                boolean invert) {
        return new PreviewOptions(mode, channel, exposureEv, rangeMin, rangeMax,
                falseColor, value, near, far, invert, cubeFace, mipLevel,
                filtering, checkerboard, updateInterval);
    }

    public PreviewOptions cube(CubeFace face, int mip) {
        return new PreviewOptions(mode, channel, exposureEv, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                face, mip, filtering, checkerboard, updateInterval);
    }

    public PreviewOptions filtering(Filtering value) {
        return new PreviewOptions(mode, channel, exposureEv, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, value, checkerboard, updateInterval);
    }

    public PreviewOptions updateInterval(int value) {
        return new PreviewOptions(mode, channel, exposureEv, rangeMin, rangeMax,
                falseColor, depthInterpretation, nearPlane, farPlane, invertDepth,
                cubeFace, mipLevel, filtering, checkerboard, value);
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    public enum Mode { AUTO, COLOR, HDR, DEPTH, CUBE }
    public enum Channel { RGBA, RGB, R, G, B, A, RG }
    public enum DepthInterpretation { RAW, PERSPECTIVE_LINEAR, ORTHOGRAPHIC_SHADOW }
    public enum CubeFace { POSITIVE_X, NEGATIVE_X, POSITIVE_Y, NEGATIVE_Y, POSITIVE_Z, NEGATIVE_Z }
    public enum Filtering { NEAREST, LINEAR }
}
