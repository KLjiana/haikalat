package com.kaleblangley.haikalat.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_MAJOR_VERSION;
import static org.lwjgl.opengl.GL30.GL_MINOR_VERSION;
import static org.lwjgl.opengl.GL32.GL_CONTEXT_CORE_PROFILE_BIT;
import static org.lwjgl.opengl.GL32.GL_CONTEXT_PROFILE_MASK;

/**
 * Defines the minimum OpenGL contract required by Haikalat's production renderer.
 *
 * <p>The report deliberately separates mandatory capabilities from optional fast paths. Callers
 * may inspect a context for diagnostics, while render entry points should use
 * {@link #requireCurrent()} so unsupported hardware fails before resource creation starts.
 */
public final class GlCapabilityContract {
    public static final int REQUIRED_MAJOR_VERSION = 4;
    public static final int REQUIRED_MINOR_VERSION = 6;

    private static final Map<GLCapabilities, Report> REPORTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Report UNAVAILABLE = evaluate(false, 0, 0, false,
            "unavailable", "unavailable", "unavailable", Set.of(), Set.of());

    private GlCapabilityContract() {
    }

    /** Returns a value report and never throws merely because no context is current. */
    public static Report inspectCurrent() {
        if (!GlDebug.hasCurrentContext()) {
            return UNAVAILABLE;
        }
        GLCapabilities capabilities = GL.getCapabilities();
        synchronized (REPORTS) {
            Report cached = REPORTS.get(capabilities);
            if (cached != null) {
                return cached;
            }
            Report detected = detect(capabilities);
            REPORTS.put(capabilities, detected);
            return detected;
        }
    }

    /**
     * Requires a current context satisfying every mandatory capability.
     *
     * @return the immutable capability report for the current context
     * @throws IllegalStateException when no context is current or a requirement is missing
     */
    public static Report requireCurrent() {
        Report report = inspectCurrent();
        if (!report.meetsRequirements()) {
            throw new IllegalStateException(report.summary());
        }
        return report;
    }

    private static Report detect(GLCapabilities capabilities) {
        int major = glGetInteger(GL_MAJOR_VERSION);
        int minor = glGetInteger(GL_MINOR_VERSION);
        boolean coreProfile = (glGetInteger(GL_CONTEXT_PROFILE_MASK)
                & GL_CONTEXT_CORE_PROFILE_BIT) != 0;
        EnumSet<Requirement> supported = EnumSet.noneOf(Requirement.class);
        if (atLeast(major, minor, REQUIRED_MAJOR_VERSION, REQUIRED_MINOR_VERSION)
                && capabilities.OpenGL46) {
            supported.add(Requirement.OPENGL_4_6);
        }
        if (coreProfile) supported.add(Requirement.CORE_PROFILE);
        if (capabilities.OpenGL45 || capabilities.GL_ARB_direct_state_access) {
            supported.add(Requirement.DIRECT_STATE_ACCESS);
        }
        if (capabilities.OpenGL43 || capabilities.GL_ARB_shader_storage_buffer_object) {
            supported.add(Requirement.SHADER_STORAGE_BUFFER);
        }
        if (capabilities.OpenGL43 || capabilities.GL_ARB_compute_shader) {
            supported.add(Requirement.COMPUTE_SHADER);
        }
        if (capabilities.OpenGL42 || capabilities.GL_ARB_shader_image_load_store) {
            supported.add(Requirement.IMAGE_LOAD_STORE);
        }
        if (capabilities.OpenGL44 || capabilities.GL_ARB_buffer_storage) {
            supported.add(Requirement.BUFFER_STORAGE);
        }
        if (capabilities.OpenGL43 || capabilities.GL_ARB_multi_draw_indirect) {
            supported.add(Requirement.MULTI_DRAW_INDIRECT);
        }
        if (capabilities.OpenGL46 || capabilities.GL_ARB_shader_draw_parameters) {
            supported.add(Requirement.SHADER_DRAW_PARAMETERS);
        }
        if (capabilities.OpenGL43 || capabilities.GL_KHR_debug) {
            supported.add(Requirement.DEBUG_OUTPUT);
        }

        EnumSet<OptionalFeature> optional = EnumSet.noneOf(OptionalFeature.class);
        if (capabilities.GL_ARB_bindless_texture) {
            optional.add(OptionalFeature.BINDLESS_TEXTURE);
        }
        GlDebug.ContextInfo identity = GlDebug.contextInfo();
        return evaluate(true, major, minor, coreProfile, identity.vendor(), identity.renderer(),
                identity.version(), supported, optional);
    }

    static Report evaluate(boolean contextAvailable, int majorVersion, int minorVersion,
                           boolean coreProfile, String vendor, String renderer, String version,
                           Set<Requirement> supported,
                           Set<OptionalFeature> optionalFeatures) {
        EnumSet<Requirement> missing = EnumSet.allOf(Requirement.class);
        missing.removeAll(supported);
        return new Report(contextAvailable, majorVersion, minorVersion, coreProfile,
                vendor, renderer, version, missing, optionalFeatures);
    }

    private static boolean atLeast(int major, int minor, int requiredMajor, int requiredMinor) {
        return major > requiredMajor || major == requiredMajor && minor >= requiredMinor;
    }

    public enum Requirement {
        OPENGL_4_6("OpenGL 4.6"),
        CORE_PROFILE("core profile"),
        DIRECT_STATE_ACCESS("direct state access"),
        SHADER_STORAGE_BUFFER("shader storage buffers"),
        COMPUTE_SHADER("compute shaders"),
        IMAGE_LOAD_STORE("image load/store"),
        BUFFER_STORAGE("immutable buffer storage"),
        MULTI_DRAW_INDIRECT("multi-draw indirect"),
        SHADER_DRAW_PARAMETERS("shader draw parameters"),
        DEBUG_OUTPUT("debug output");

        private final String displayName;

        Requirement(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum OptionalFeature {
        BINDLESS_TEXTURE("bindless textures");

        private final String displayName;

        OptionalFeature(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Immutable result suitable for logs, diagnostics, and startup error reporting. */
    public static final class Report {
        private final boolean contextAvailable;
        private final int majorVersion;
        private final int minorVersion;
        private final boolean coreProfile;
        private final String vendor;
        private final String renderer;
        private final String version;
        private final Set<Requirement> missingRequirements;
        private final Set<OptionalFeature> optionalFeatures;

        private Report(boolean contextAvailable, int majorVersion, int minorVersion,
                       boolean coreProfile, String vendor, String renderer, String version,
                       Set<Requirement> missingRequirements,
                       Set<OptionalFeature> optionalFeatures) {
            this.contextAvailable = contextAvailable;
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
            this.coreProfile = coreProfile;
            this.vendor = vendor;
            this.renderer = renderer;
            this.version = version;
            this.missingRequirements = Set.copyOf(missingRequirements);
            this.optionalFeatures = Set.copyOf(optionalFeatures);
        }

        public boolean contextAvailable() {
            return contextAvailable;
        }

        public int majorVersion() {
            return majorVersion;
        }

        public int minorVersion() {
            return minorVersion;
        }

        public boolean coreProfile() {
            return coreProfile;
        }

        public String vendor() {
            return vendor;
        }

        public String renderer() {
            return renderer;
        }

        public String version() {
            return version;
        }

        public Set<Requirement> missingRequirements() {
            return missingRequirements;
        }

        public Set<OptionalFeature> optionalFeatures() {
            return optionalFeatures;
        }

        public boolean meetsRequirements() {
            return contextAvailable && missingRequirements.isEmpty();
        }

        public String summary() {
            if (!contextAvailable) {
                return "OpenGL capability contract failed: no current GLFW/OpenGL context "
                        + "with LWJGL capabilities";
            }
            String detected = majorVersion + "." + minorVersion
                    + (coreProfile ? " core" : " compatibility")
                    + ", vendor=" + vendor + ", renderer=" + renderer
                    + ", driver=" + version;
            if (!missingRequirements.isEmpty()) {
                String missing = missingRequirements.stream()
                        .map(Requirement::displayName)
                        .sorted()
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("unknown");
                return "OpenGL capability contract failed: missing [" + missing
                        + "]; detected " + detected;
            }
            String optional = optionalFeatures.isEmpty() ? "none"
                    : optionalFeatures.stream().map(OptionalFeature::displayName).sorted()
                    .reduce((left, right) -> left + ", " + right).orElse("none");
            return "OpenGL capability contract satisfied: " + detected
                    + ", optional=[" + optional + "]";
        }

        @Override
        public String toString() {
            return summary();
        }
    }
}
