package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.opengl.GL11.GL_CLAMP;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetTexImage;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_RG32F;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform1f;
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

/** Numeric regression coverage for GTAO reconstruction shaders. */
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class GtaoReconstructionGlTest {
    private static final String FULLSCREEN_VERTEX = """
            #version 460 core
            out vec2 vTexCoord;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vTexCoord = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    @Test
    void upsampleKeepsCenterAlignedLinearRampAndConstantFields() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("GTAO reconstruction").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int input = texture(8, 8, ramp(8, 8));
            int depth = texture(16, 16, filled(16 * 16, 0.9f));
            int normal = rgTexture(8, 8, filled(8 * 8 * 2, 0.5f));
            int output = texture(16, 16, new float[16 * 16]);
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, output, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            int program = program(resource("/shaders/postprocess/gtao-upsample.frag"));
            try {
                glUseProgram(program);
                uniformInt(program, "uInput", 0);
                uniformInt(program, "uDepth", 1);
                uniformInt(program, "uNormal", 2);
                uniform2(program, "uHalfExtent", 8, 8);
                uniform2(program, "uFullExtent", 16, 16);
                uniform1(program, "uDepthReject", 0.02f);
                uniformIdentity(program, "uInverseProjection");
                glBindTextureUnit(0, input);
                glBindTextureUnit(1, depth);
                glBindTextureUnit(2, normal);
                glViewport(0, 0, 16, 16);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                float[] result = readTexture(output, 16, 16);
                assertEquals(0.25f, result[8 * 16 + 4], 1.0e-3f);
                assertEquals(0.321429f, result[8 * 16 + 5], 2.0e-3f);
                assertEquals(0.392857f, result[8 * 16 + 6], 2.0e-3f);
                assertEquals(0.464286f, result[8 * 16 + 7], 2.0e-3f);

                uploadTexture(input, 8, 8, filled(8 * 8, 0.37f));
                glDrawArrays(GL_TRIANGLES, 0, 3);
                float[] constant = readTexture(output, 16, 16);
                for (float value : constant) assertEquals(0.37f, value, 1.0e-5f);
            } finally {
                glDeleteProgram(program);
                glDeleteFramebuffers(framebuffer);
                glDeleteTextures(input);
                glDeleteTextures(depth);
                glDeleteTextures(normal);
                glDeleteTextures(output);
                glDeleteVertexArrays(vao);
            }
            assertEquals(GL_NO_ERROR, glGetError());
            GlDebug.checkError("gtaoUpsampleNumericRegression");
        }
    }

    @Test
    void viewSpaceNormalDoesNotBridgeAdepthDiscontinuity() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(32, 32).title("GTAO normal reconstruction").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            float[] depthValues = new float[16 * 16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) depthValues[y * 16 + x] = x < 8 ? 0.50f : 0.70f;
            }
            int depth = texture(16, 16, depthValues);
            int output = texture(16, 16, new float[16 * 16]);
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, output, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            String source = resource("/shaders/postprocess/gtao-upsample.frag");
            String normalSource = source.substring(0, source.indexOf("void main()")) + """
                    void main() {
                        float depth = texture(uDepth, vTexCoord).r;
                        vec3 view = reconstructView(vTexCoord, depth);
                        FragVisibility = reconstructNormal(vTexCoord, depth, view).z;
                    }
                    """;
            int program = program(normalSource);
            try {
                glUseProgram(program);
                uniformInt(program, "uDepth", 1);
                uniform2(program, "uFullExtent", 16, 16);
                uniformIdentity(program, "uInverseProjection");
                glBindTextureUnit(1, depth);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                float[] result = readTexture(output, 16, 16);
                assertTrue(result[8 * 16 + 6] > 0.98f,
                        "left plane normal must remain front-facing");
                assertTrue(result[8 * 16 + 9] > 0.98f,
                        "right plane normal must remain front-facing");
            } finally {
                glDeleteProgram(program);
                glDeleteFramebuffers(framebuffer);
                glDeleteTextures(depth);
                glDeleteTextures(output);
                glDeleteVertexArrays(vao);
            }
            assertEquals(GL_NO_ERROR, glGetError());
            GlDebug.checkError("gtaoNormalNumericRegression");
        }
    }

    @Test
    void productionSliceIntegralMatchesIndependentAngularQuadrature() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(16, 16).title("GTAO slice integral").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int output = texture(1, 1, new float[1]);
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, output, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            String source = resource("/shaders/postprocess/gtao-estimate.frag");
            // Exercise the exact production projection and integral functions.
            // The CPU oracle below integrates radiance numerically, without
            // copying the shader's trigonometric antiderivative.
            int program = program(source.substring(0, source.indexOf("void main()")) + """
                    uniform vec3 uTestNormal;
                    uniform vec3 uTestView;
                    uniform vec3 uTestDirection;
                    uniform vec2 uTestHorizon;
                    void main() {
                        FragVisibility = integrateSliceVisibility(uTestHorizon,
                            projectSliceNormal(uTestNormal, uTestView, uTestDirection));
                        FragNormal = vec2(0.5);
                    }
                    """);
            try {
                glUseProgram(program);
                glViewport(0, 0, 1, 1);
                double[][] cases = {
                        // normal tilt/azimuth, view tilt/azimuth, slice azimuth,
                        // positive and negative horizon angles from view ray.
                        {0, 0, 0, 0, 0, 60, 60},
                        {35, 0, 0, 0, 0, 80, 25},
                        {55, 70, 0, 0, 15, 35, 100},
                        {40, 65, 25, 10, 95, 75, 30},
                        {40, 155, 25, 100, 185, 75, 30},
                        {70, 0, 0, 0, 0, 160, 20},
                        {0, 0, 0, 0, 0, 90, 90}
                };
                for (double[] sample : cases) {
                    double[] normal = spherical(sample[0], sample[1]);
                    double[] view = spherical(sample[2], sample[3]);
                    double angle = Math.toRadians(sample[4]);
                    double[] direction = {Math.cos(angle), Math.sin(angle), 0};
                    uniform3(program, "uTestNormal", normal);
                    uniform3(program, "uTestView", view);
                    uniform3(program, "uTestDirection", direction);
                    uniform2(program, "uTestHorizon",
                            (float) Math.cos(Math.toRadians(sample[5])),
                            (float) Math.cos(Math.toRadians(sample[6])));
                    glDrawArrays(GL_TRIANGLES, 0, 3);
                    double expected = numericalSliceVisibility(normal, view, direction,
                            Math.toRadians(sample[5]), Math.toRadians(sample[6]));
                    // GLSL transcendental approximations need more room than
                    // double quadrature, but remain far below one R8 AO step.
                    assertEquals(expected, readTexture(output, 1, 1)[0], 2.0e-4,
                            "slice " + Arrays.toString(sample));
                }
            } finally {
                glDeleteProgram(program);
                glDeleteFramebuffers(framebuffer);
                glDeleteTextures(output);
                glDeleteVertexArrays(vao);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    @Test
    void productionEstimateLeavesUnoccludedPerspectivePlanesVisible() {
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(64, 64).title("GTAO plane integration").visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int depth = texture(64, 64, new float[64 * 64]);
            int output = texture(32, 32, new float[32 * 32]);
            int framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, output, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            int program = program(resource("/shaders/postprocess/gtao-estimate.frag"));
            try {
                glUseProgram(program);
                glViewport(0, 0, 32, 32);
                uniformInt(program, "uDepth", 0);
                uniformInt(program, "uDirections", 6);
                uniformInt(program, "uSteps", 6);
                uniform1(program, "uRadius", 1);
                uniform1(program, "uStrength", 1);
                uniform1(program, "uThickness", 0.05f);
                uniform1(program, "uProjectionScaleY", (float) Math.sqrt(3));
                uniform2(program, "uFullExtent", 64, 64);
                FloatBuffer inverse = BufferUtils.createFloatBuffer(16);
                new org.joml.Matrix4f().perspective((float) Math.toRadians(60), 1, 0.1f, 100)
                        .invert().get(inverse);
                org.lwjgl.opengl.GL20.glUniformMatrix4fv(
                        glGetUniformLocation(program, "uInverseProjection"), false, inverse);
                for (double tilt : new double[]{0, 35}) {
                    for (double azimuth : new double[]{0, 65, 155}) {
                        double[] normal = spherical(tilt, azimuth);
                        float[] depths = new float[64 * 64];
                        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                            double rx = (2 * (x + 0.5) / 64 - 1) / Math.sqrt(3);
                            double ry = (2 * (y + 0.5) / 64 - 1) / Math.sqrt(3);
                            double distance = -5 * normal[2]
                                    / (normal[0] * rx + normal[1] * ry - normal[2]);
                            depths[y * 64 + x] = (float) (100 / 99.9 - 10 / (99.9 * distance));
                        }
                        uploadTexture(depth, 64, 64, depths);
                        glBindTextureUnit(0, depth);
                        glDrawArrays(GL_TRIANGLES, 0, 3);
                        float[] values = readTexture(output, 32, 32);
                        for (int y = 12; y < 20; y++) for (int x = 12; x < 20; x++) {
                            assertEquals(1, values[y * 32 + x], 0.04,
                                    "unoccluded plane tilt=" + tilt + " azimuth=" + azimuth);
                        }
                    }
                }
            } finally {
                glDeleteProgram(program);
                glDeleteFramebuffers(framebuffer);
                glDeleteTextures(depth);
                glDeleteTextures(output);
                glDeleteVertexArrays(vao);
            }
            assertEquals(GL_NO_ERROR, glGetError());
        }
    }

    private static double numericalSliceVisibility(double[] normal, double[] view,
                                                   double[] direction,
                                                   double positive, double negative) {
        double dot = dot(direction, view);
        double[] tangent = {direction[0] - dot * view[0],
                direction[1] - dot * view[1], direction[2] - dot * view[2]};
        double length = Math.sqrt(dot(tangent, tangent));
        for (int i = 0; i < 3; i++) tangent[i] /= length;
        double normalView = dot(normal, view);
        double normalTangent = dot(normal, tangent);
        int steps = 131_072;
        double delta = (positive + negative) / steps;
        double sum = 0;
        for (int i = 0; i < steps; i++) {
            double theta = -negative + (i + 0.5) * delta;
            double cosine = normalView * Math.cos(theta) + normalTangent * Math.sin(theta);
            sum += Math.max(0, cosine) * Math.abs(Math.sin(theta)) * delta;
        }
        return sum;
    }

    private static double[] spherical(double tilt, double azimuth) {
        double t = Math.toRadians(tilt), a = Math.toRadians(azimuth);
        return new double[]{Math.sin(t) * Math.cos(a), Math.sin(t) * Math.sin(a), Math.cos(t)};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void uniform3(int program, String name, double[] value) {
        org.lwjgl.opengl.GL20.glUniform3f(glGetUniformLocation(program, name),
                (float) value[0], (float) value[1], (float) value[2]);
    }

    private static int program(String fragment) {
        int vertex = shader(GL_VERTEX_SHADER, FULLSCREEN_VERTEX);
        int pixel = shader(GL_FRAGMENT_SHADER, fragment);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, pixel);
        glLinkProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(pixel);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException(glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException(glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String resource(String path) {
        try (var stream = GtaoReconstructionGlTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("missing shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to read shader " + path, failure);
        }
    }

    private static int texture(int width, int height, float[] data) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, width, height, 0, GL_RED, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        return texture;
    }

    private static int rgTexture(int width, int height, float[] data) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, width, height, 0, GL_RG, GL_FLOAT, data);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);
        return texture;
    }

    private static void uploadTexture(int texture, int width, int height, float[] data) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, width, height, 0, GL_RED, GL_FLOAT, data);
    }

    private static float[] readTexture(int texture, int width, int height) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(width * height);
        glBindTexture(GL_TEXTURE_2D, texture);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED, GL_FLOAT, buffer);
        float[] values = new float[width * height];
        buffer.get(values);
        return values;
    }

    private static float[] filled(int size, float value) {
        float[] data = new float[size];
        Arrays.fill(data, value);
        return data;
    }

    private static float[] ramp(int width, int height) {
        float[] data = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) data[y * width + x] = x / (float) (width - 1);
        }
        return data;
    }

    private static void uniformInt(int program, String name, int value) {
        glUniform1i(glGetUniformLocation(program, name), value);
    }

    private static void uniform1(int program, String name, float value) {
        glUniform1f(glGetUniformLocation(program, name), value);
    }

    private static void uniform2(int program, String name, float x, float y) {
        glUniform2f(glGetUniformLocation(program, name), x, y);
    }

    private static void uniformIdentity(int program, String name) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) {
            FloatBuffer identity = BufferUtils.createFloatBuffer(16);
            identity.put(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).flip();
            org.lwjgl.opengl.GL20.glUniformMatrix4fv(location, false, identity);
        }
    }
}
