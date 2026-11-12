package com.kaleblangley.haikalat.backend.vrs;

import com.kaleblangley.haikalat.backend.GlDebug;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.FunctionProvider;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.opengl.GL11.GL_EXTENSIONS;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_NUM_EXTENSIONS;
import static org.lwjgl.opengl.GL30.glGetStringi;
import static org.lwjgl.opengl.NVShadingRateImage.GL_MAX_COARSE_FRAGMENT_SAMPLES_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_PALETTE_SIZE_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_TEXEL_HEIGHT_NV;
import static org.lwjgl.opengl.NVShadingRateImage.GL_SHADING_RATE_IMAGE_TEXEL_WIDTH_NV;

/**
 * Runtime answer to "can this context shade fragments at a variable rate?".
 *
 * <p>The decision never assumes that a GPU family, an RTX marketing name or a
 * driver version implies support.  {@link #inspectCurrent()} enumerates the
 * exact extension strings exposed by the current context, verifies that the
 * function entry points LWJGL resolved are non-zero, and only then queries the
 * implementation limits.  {@link #evaluate} is a pure factory so the decision
 * rules can be unit-tested without an OpenGL context.</p>
 *
 * <p>Two extensions are recognised:</p>
 *
 * <ul>
 *   <li>{@code GL_NV_shading_rate_image} — the implemented per-tile path: an
 *       {@code R8UI} image selected per 16x16 (implementation-dependent) tile
 *       through a palette, bound to the draw framebuffer.</li>
 *   <li>{@code GL_EXT_fragment_shading_rate} — detected and reported, but not
 *       used.  LWJGL 3.3.3 ships no generated bindings for that extension, so a
 *       call would require hand-written native function pointers and additional
 *       attachment state; the NVIDIA image path already provides a real
 *       selective path on the target driver.  Reporting it explicitly keeps the
 *       capability answer honest instead of treating it as support.</li>
 * </ul>
 */
public record VariableRateShadingCapability(
        boolean contextAvailable,
        boolean nvExtensionAdvertised,
        boolean nvEntryPointsLoaded,
        boolean nvPrimitiveShadingRateAdvertised,
        boolean extExtensionAdvertised,
        boolean extEntryPointsLoaded,
        int texelWidth,
        int texelHeight,
        int paletteSize,
        int maxCoarseSamples) {

    /** Exact extension string for the implemented per-tile image path. */
    public static final String NV_SHADING_RATE_IMAGE = "GL_NV_shading_rate_image";
    /** Exact extension string for the cross-vendor per-draw/attachment path. */
    public static final String EXT_FRAGMENT_SHADING_RATE = "GL_EXT_fragment_shading_rate";
    /** Exact extension string for the vendor per-primitive rate path. */
    public static final String NV_PRIMITIVE_SHADING_RATE = "GL_NV_primitive_shading_rate";

    private static final String EXT_RATE_FUNCTION = "glShadingRateEXT";
    private static final String EXT_FRAMEBUFFER_RATE_FUNCTION = "glFramebufferShadingRateEXT";
    private static final String NV_BIND_FUNCTION = "glBindShadingRateImageNV";
    private static final String NV_PALETTE_FUNCTION = "glShadingRateImagePaletteNV";
    private static final String NV_GET_PALETTE_FUNCTION = "glGetShadingRateImagePaletteNV";

    /** The single effective outcome for a runtime probe. */
    public enum Status {
        /** The per-tile NV image path can be used. */
        SUPPORTED_NV_IMAGE,
        /** Only the EXT extension is present; that path is not implemented. */
        EXT_DETECTED_NOT_IMPLEMENTED,
        /** No usable selective shading-rate path exists in this context. */
        UNAVAILABLE
    }

    /**
     * Pure capability evaluation used by tests and by {@link #inspectCurrent()}.
     *
     * @param contextAvailable             whether a current GL context exists
     * @param nvExtensionAdvertised        exact {@code GL_NV_shading_rate_image} string present
     * @param nvEntryPointsLoaded           required NV entry points resolved non-zero
     * @param nvPrimitiveShadingRateAdvertised {@code GL_NV_primitive_shading_rate} string present
     * @param extExtensionAdvertised       exact {@code GL_EXT_fragment_shading_rate} string present
     * @param extEntryPointsLoaded         EXT entry points resolved non-zero
     * @param texelWidth                   queried NV rate-image texel width, or zero
     * @param texelHeight                  queried NV rate-image texel height, or zero
     * @param paletteSize                  queried NV palette size, or zero
     * @param maxCoarseSamples             queried NV maximum coarse samples, or zero
     * @return an immutable capability value
     */
    public static VariableRateShadingCapability evaluate(
            boolean contextAvailable,
            boolean nvExtensionAdvertised,
            boolean nvEntryPointsLoaded,
            boolean nvPrimitiveShadingRateAdvertised,
            boolean extExtensionAdvertised,
            boolean extEntryPointsLoaded,
            int texelWidth,
            int texelHeight,
            int paletteSize,
            int maxCoarseSamples) {
        return new VariableRateShadingCapability(contextAvailable, nvExtensionAdvertised,
                nvEntryPointsLoaded, nvPrimitiveShadingRateAdvertised, extExtensionAdvertised,
                extEntryPointsLoaded, Math.max(0, texelWidth), Math.max(0, texelHeight),
                Math.max(0, paletteSize), Math.max(0, maxCoarseSamples));
    }

    /** A context-less answer suitable for construction tests and early diagnostics. */
    public static VariableRateShadingCapability unavailable() {
        return evaluate(false, false, false, false, false, false, 0, 0, 0, 0);
    }

    /**
     * Probes the current context.  This method never throws because support is
     * optional; callers read {@link #status()} and report the reason.
     *
     * @return the immutable capability of the current context, or
     *         {@link #unavailable()} when no context is current
     */
    public static VariableRateShadingCapability inspectCurrent() {
        if (!GlDebug.hasCurrentContext()) {
            return unavailable();
        }
        GLCapabilities capabilities = org.lwjgl.opengl.GL.getCapabilities();
        Set<String> extensions = extensionStrings();
        boolean nvAdvertised = extensions.contains(NV_SHADING_RATE_IMAGE);
        boolean extAdvertised = extensions.contains(EXT_FRAGMENT_SHADING_RATE);
        boolean primitiveAdvertised = extensions.contains(NV_PRIMITIVE_SHADING_RATE);
        boolean nvEntryPoints = nvAdvertised
                && capabilities.glBindShadingRateImageNV != 0L
                && capabilities.glShadingRateImagePaletteNV != 0L
                && capabilities.glGetShadingRateImagePaletteNV != 0L
                && capabilities.glShadingRateImageBarrierNV != 0L;
        boolean extEntryPoints = extAdvertised
                && functionAddress(EXT_RATE_FUNCTION) != 0L
                && functionAddress(EXT_FRAMEBUFFER_RATE_FUNCTION) != 0L;
        int texelWidth = 0;
        int texelHeight = 0;
        int paletteSize = 0;
        int maxCoarseSamples = 0;
        if (nvAdvertised && nvEntryPoints) {
            texelWidth = glGetInteger(GL_SHADING_RATE_IMAGE_TEXEL_WIDTH_NV);
            texelHeight = glGetInteger(GL_SHADING_RATE_IMAGE_TEXEL_HEIGHT_NV);
            paletteSize = glGetInteger(GL_SHADING_RATE_IMAGE_PALETTE_SIZE_NV);
            maxCoarseSamples = glGetInteger(GL_MAX_COARSE_FRAGMENT_SAMPLES_NV);
        }
        return evaluate(true, nvAdvertised, nvEntryPoints, primitiveAdvertised,
                extAdvertised, extEntryPoints, texelWidth, texelHeight, paletteSize,
                maxCoarseSamples);
    }

    /** @return the single effective outcome for this capability */
    public Status status() {
        if (!contextAvailable) return Status.UNAVAILABLE;
        if (nvExtensionAdvertised && nvEntryPointsLoaded
                && texelWidth > 0 && texelHeight > 0 && paletteSize >= 2) {
            return Status.SUPPORTED_NV_IMAGE;
        }
        if (extExtensionAdvertised && extEntryPointsLoaded) {
            return Status.EXT_DETECTED_NOT_IMPLEMENTED;
        }
        return Status.UNAVAILABLE;
    }

    /** @return whether the implemented NV per-tile image path may be used */
    public boolean nvImagePathAvailable() {
        return status() == Status.SUPPORTED_NV_IMAGE;
    }

    /**
     * Explains why the implemented path is not usable.
     *
     * @return an empty string when {@link #nvImagePathAvailable()} is true
     */
    public String unsupportedReason() {
        if (nvImagePathAvailable()) return "";
        if (!contextAvailable) {
            return "no current OpenGL context";
        }
        if (nvExtensionAdvertised && !nvEntryPointsLoaded) {
            return NV_SHADING_RATE_IMAGE + " is advertised but one or more required entry "
                    + "points are missing (" + NV_BIND_FUNCTION + ", " + NV_PALETTE_FUNCTION
                    + ", " + NV_GET_PALETTE_FUNCTION + ", glShadingRateImageBarrierNV)";
        }
        if (nvExtensionAdvertised && nvEntryPointsLoaded) {
            return NV_SHADING_RATE_IMAGE + " entry points are present but the queried limits are "
                    + "invalid (texel " + texelWidth + "x" + texelHeight + ", palette "
                    + paletteSize + ")";
        }
        if (extExtensionAdvertised && extEntryPointsLoaded) {
            return "only " + EXT_FRAGMENT_SHADING_RATE + " is available; the EXT path is not "
                    + "implemented (LWJGL 3.3.3 has no generated EXT_fragment_shading_rate "
                    + "bindings), and " + NV_SHADING_RATE_IMAGE + " is absent";
        }
        if (extExtensionAdvertised && !extEntryPointsLoaded) {
            return EXT_FRAGMENT_SHADING_RATE + " is advertised but its entry points are missing ("
                    + EXT_RATE_FUNCTION + ", " + EXT_FRAMEBUFFER_RATE_FUNCTION + ")";
        }
        return "neither " + NV_SHADING_RATE_IMAGE + " nor " + EXT_FRAGMENT_SHADING_RATE
                + " is advertised by the current context";
    }

    /** @return a stable one-line diagnostic suitable for logs and reports */
    public String summary() {
        String extensionState = "extensions=[" + (nvExtensionAdvertised
                ? NV_SHADING_RATE_IMAGE : "-") + "," + (extExtensionAdvertised
                ? EXT_FRAGMENT_SHADING_RATE : "-") + "," + (nvPrimitiveShadingRateAdvertised
                ? NV_PRIMITIVE_SHADING_RATE : "-") + "]";
        String entryState = "entryPoints=[nv=" + nvEntryPointsLoaded
                + ",ext=" + extEntryPointsLoaded + "]";
        String limitState = "limits=[texel=" + texelWidth + "x" + texelHeight
                + ",palette=" + paletteSize + ",maxCoarseSamples=" + maxCoarseSamples + "]";
        String outcome = status() == Status.SUPPORTED_NV_IMAGE
                ? "active=" + status()
                : "fallback=" + status() + " (" + unsupportedReason() + ")";
        return "variable-rate-shading " + outcome + " " + extensionState + " " + entryState
                + " " + limitState;
    }

    private static Set<String> extensionStrings() {
        int count = glGetInteger(GL_NUM_EXTENSIONS);
        if (count <= 0) return Set.of();
        Set<String> extensions = new HashSet<>(Math.max(16, count));
        for (int index = 0; index < count; index++) {
            String name = glGetStringi(GL_EXTENSIONS, index);
            if (name != null && !name.isBlank()) {
                extensions.add(name);
            }
        }
        return Collections.unmodifiableSet(extensions);
    }

    private static long functionAddress(String name) {
        FunctionProvider provider = org.lwjgl.opengl.GL.getFunctionProvider();
        if (provider == null) return 0L;
        try {
            return provider.getFunctionAddress(name);
        } catch (RuntimeException ignored) {
            // A missing symbol is a normal unsupported state, not a probe failure.
            return 0L;
        }
    }
}
