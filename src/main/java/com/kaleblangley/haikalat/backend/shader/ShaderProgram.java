package com.kaleblangley.haikalat.backend.shader;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FALSE;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
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
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL31.GL_INVALID_INDEX;
import static org.lwjgl.opengl.GL31.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL31.glUniformBlockBinding;
import static org.lwjgl.opengl.GL41.glProgramUniform1f;
import static org.lwjgl.opengl.GL41.glProgramUniform1i;
import static org.lwjgl.opengl.GL41.glProgramUniform1ui;
import static org.lwjgl.opengl.GL41.glProgramUniform2f;
import static org.lwjgl.opengl.GL41.glProgramUniform3f;
import static org.lwjgl.opengl.GL41.glProgramUniform4f;
import static org.lwjgl.opengl.GL41.glProgramUniformMatrix3fv;
import static org.lwjgl.opengl.GL41.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL43.GL_PROGRAM;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BLOCK;
import static org.lwjgl.opengl.GL43.glGetProgramResourceIndex;
import static org.lwjgl.opengl.GL43.glShaderStorageBlockBinding;

/**
 * Linked OpenGL shader program with stage-aware construction and DSA uniform updates.
 *
 * <p>The object is owned by one OpenGL context thread. Consequently its lookup caches use
 * ordinary maps instead of concurrent maps. Name lookups happen once; commands can retain the
 * resulting integer location and update this program without making it current first.</p>
 */
public final class ShaderProgram implements GlResource {
    private static final int RECENT_UNIFORM_SET_COUNT = 32;
    private final int id;
    private final Set<ShaderStage> stages;
    private final long resourceSequence;
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private final Map<String, Integer> uniformBlockIndices = new HashMap<>();
    private final Map<String, Integer> storageBlockIndices = new HashMap<>();
    private final FloatBuffer matrix3Scratch = BufferUtils.createFloatBuffer(9);
    private final FloatBuffer matrix4Scratch = BufferUtils.createFloatBuffer(16);
    private final String[] recentUniformNames = new String[RECENT_UNIFORM_SET_COUNT * 2];
    private final int[] recentUniformLocations = new int[RECENT_UNIFORM_SET_COUNT * 2];
    private String lastUniformName;
    private int lastUniformLocation;
    private boolean closed;

    private ShaderProgram(int id) {
        this(id, Set.of());
    }

    private ShaderProgram(int id, Set<ShaderStage> stages) {
        this.id = id;
        this.stages = Set.copyOf(stages);
        this.resourceSequence = GlDebug.trackResource("PROGRAM", id,
                stages.isEmpty() ? "ShaderProgram" : "ShaderProgram " + stages, -1L);
        GlDebug.labelObject(GL_PROGRAM, id, stages.isEmpty()
                ? "ShaderProgram"
                : "ShaderProgram " + stages);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Compatibility helper for the most common graphics pipeline. */
    public static ShaderProgram fromSources(String vertexSource, String fragmentSource) {
        return builder()
                .stage(ShaderStage.VERTEX, vertexSource, "vertex source")
                .stage(ShaderStage.FRAGMENT, fragmentSource, "fragment source")
                .link();
    }

    /** Compatibility helper for classpath vertex/fragment resources. */
    public static ShaderProgram fromResource(Class<?> anchor, String vertexPath, String fragmentPath) {
        return builder()
                .resource(anchor, ShaderStage.VERTEX, vertexPath)
                .resource(anchor, ShaderStage.FRAGMENT, fragmentPath)
                .link();
    }

    public static ShaderProgram fromComputeSource(String computeSource) {
        return builder().stage(ShaderStage.COMPUTE, computeSource, "compute source").link();
    }

    public static ShaderProgram fromComputeResource(Class<?> anchor, String computePath) {
        return builder().resource(anchor, ShaderStage.COMPUTE, computePath).link();
    }

    public Set<ShaderStage> stages() {
        return stages;
    }

    public boolean hasStage(ShaderStage stage) {
        return stages.contains(Objects.requireNonNull(stage, "stage"));
    }

    public boolean isCompute() {
        return stages.size() == 1 && stages.contains(ShaderStage.COMPUTE);
    }

    public ShaderProgram use() {
        ensureOpen();
        glUseProgram(id);
        return this;
    }

    public ShaderProgram stopUsing() {
        glUseProgram(0);
        return this;
    }

    public ShaderProgram setBool(String name, boolean value) {
        return setInt(name, value ? 1 : 0);
    }

    public ShaderProgram setInt(String name, int value) {
        return setInt(uniformLocation(name), value);
    }

    public ShaderProgram setInt(int location, int value) {
        ensureOpen();
        glProgramUniform1i(id, requireLocation(location), value);
        return this;
    }

    public ShaderProgram setUInt(String name, int value) {
        return setUInt(uniformLocation(name), value);
    }

    public ShaderProgram setUInt(int location, int value) {
        ensureOpen();
        glProgramUniform1ui(id, requireLocation(location), value);
        return this;
    }

    public ShaderProgram setFloat(String name, float value) {
        return setFloat(uniformLocation(name), value);
    }

    public ShaderProgram setFloat(int location, float value) {
        ensureOpen();
        glProgramUniform1f(id, requireLocation(location), value);
        return this;
    }

    public ShaderProgram setVec2(String name, float x, float y) {
        return setVec2(uniformLocation(name), x, y);
    }

    public ShaderProgram setVec2(String name, Vector2fc value) {
        Objects.requireNonNull(value, "value");
        return setVec2(name, value.x(), value.y());
    }

    public ShaderProgram setVec2(int location, float x, float y) {
        ensureOpen();
        glProgramUniform2f(id, requireLocation(location), x, y);
        return this;
    }

    public ShaderProgram setVec3(String name, Vector3fc value) {
        Objects.requireNonNull(value, "value");
        return setVec3(uniformLocation(name), value.x(), value.y(), value.z());
    }

    public ShaderProgram setVec3(int location, float x, float y, float z) {
        ensureOpen();
        glProgramUniform3f(id, requireLocation(location), x, y, z);
        return this;
    }

    public ShaderProgram setVec4(String name, Vector4fc value) {
        Objects.requireNonNull(value, "value");
        return setVec4(uniformLocation(name), value.x(), value.y(), value.z(), value.w());
    }

    public ShaderProgram setVec4(String name, float x, float y, float z, float w) {
        return setVec4(uniformLocation(name), x, y, z, w);
    }

    public ShaderProgram setVec4(int location, float x, float y, float z, float w) {
        ensureOpen();
        glProgramUniform4f(id, requireLocation(location), x, y, z, w);
        return this;
    }

    public ShaderProgram setMat3(String name, Matrix3fc value) {
        return setMat3(uniformLocation(name), value);
    }

    public ShaderProgram setMat3(int location, Matrix3fc value) {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        matrix3Scratch.clear();
        value.get(matrix3Scratch);
        glProgramUniformMatrix3fv(id, requireLocation(location), false, matrix3Scratch);
        return this;
    }

    public ShaderProgram setMat4(String name, Matrix4fc value) {
        return setMat4(uniformLocation(name), value);
    }

    public ShaderProgram setMat4(int location, Matrix4fc value) {
        ensureOpen();
        Objects.requireNonNull(value, "value");
        matrix4Scratch.clear();
        value.get(matrix4Scratch);
        glProgramUniformMatrix4fv(id, requireLocation(location), false, matrix4Scratch);
        return this;
    }

    /** A sampler is an integer texture-unit uniform; this name makes that intent explicit. */
    public ShaderProgram setSampler(String name, int textureUnit) {
        return setInt(name, textureUnit);
    }

    /** An image is also selected through an integer image-unit uniform. */
    public ShaderProgram setImage(String name, int imageUnit) {
        return setInt(name, imageUnit);
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        glDeleteProgram(id);
        GlDebug.closeResource(resourceSequence);
        uniformLocations.clear();
        uniformBlockIndices.clear();
        storageBlockIndices.clear();
        lastUniformName = null;
        for (int index = 0; index < recentUniformNames.length; index++) {
            recentUniformNames[index] = null;
        }
        closed = true;
    }

    public int uniformLocation(String name) {
        int location = uniformLocationOrMinusOne(name);
        if (location < 0) throw new GlException("Uniform not found: " + name);
        return location;
    }

    public int uniformLocationOrMinusOne(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        if (name == lastUniformName || name.equals(lastUniformName)) {
            return lastUniformLocation;
        }
        int recentSlot = System.identityHashCode(name) & (RECENT_UNIFORM_SET_COUNT - 1);
        if (recentUniformNames[recentSlot] == name) {
            return rememberLastUniform(name, recentUniformLocations[recentSlot]);
        }
        int secondSlot = recentSlot + RECENT_UNIFORM_SET_COUNT;
        if (recentUniformNames[secondSlot] == name) {
            return rememberLastUniform(name, recentUniformLocations[secondSlot]);
        }
        Integer cached = uniformLocations.get(name);
        int location;
        if (cached != null) {
            location = cached;
        } else {
            location = glGetUniformLocation(id, name);
            uniformLocations.put(name, location);
        }
        recentUniformNames[secondSlot] = recentUniformNames[recentSlot];
        recentUniformLocations[secondSlot] = recentUniformLocations[recentSlot];
        recentUniformNames[recentSlot] = name;
        recentUniformLocations[recentSlot] = location;
        return rememberLastUniform(name, location);
    }

    private int rememberLastUniform(String name, int location) {
        lastUniformName = name;
        lastUniformLocation = location;
        return location;
    }

    public boolean hasUniform(String name) {
        return uniformLocationOrMinusOne(name) >= 0;
    }

    public int uniformBlockIndex(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        Integer cached = uniformBlockIndices.get(name);
        if (cached != null) return cached;
        int index = glGetUniformBlockIndex(id, name);
        if (index == GL_INVALID_INDEX) throw new GlException("Uniform block not found: " + name);
        uniformBlockIndices.put(name, index);
        return index;
    }

    public int storageBlockIndex(String name) {
        ensureOpen();
        Objects.requireNonNull(name, "name");
        Integer cached = storageBlockIndices.get(name);
        if (cached != null) return cached;
        int index = glGetProgramResourceIndex(id, GL_SHADER_STORAGE_BLOCK, name);
        if (index == GL_INVALID_INDEX) {
            throw new GlException("Shader storage block not found: " + name);
        }
        storageBlockIndices.put(name, index);
        return index;
    }

    public ShaderProgram bindUniformBlock(String blockName, int bindingPoint) {
        requireBindingPoint(bindingPoint);
        glUniformBlockBinding(id, uniformBlockIndex(blockName), bindingPoint);
        return this;
    }

    public ShaderProgram bindStorageBlock(String blockName, int bindingPoint) {
        requireBindingPoint(bindingPoint);
        glShaderStorageBlockBinding(id, storageBlockIndex(blockName), bindingPoint);
        return this;
    }

    private void ensureOpen() {
        if (closed) throw new GlException("Shader program is closed");
    }

    private static int requireLocation(int location) {
        if (location < 0) throw new IllegalArgumentException("uniform location must be non-negative");
        return location;
    }

    private static void requireBindingPoint(int bindingPoint) {
        if (bindingPoint < 0) throw new IllegalArgumentException("binding point must be non-negative");
    }

    private static ShaderProgram link(EnumMap<ShaderStage, SourceUnit> sources) {
        validateStages(sources);
        int program = glCreateProgram();
        List<Integer> compiled = new ArrayList<>(sources.size());
        try {
            for (Map.Entry<ShaderStage, SourceUnit> entry : sources.entrySet()) {
                int shader = compileShader(entry.getKey(), entry.getValue());
                compiled.add(shader);
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                throw new GlException("Program link failed for " + sources.keySet() + ":\n"
                        + glGetProgramInfoLog(program));
            }
            return new ShaderProgram(program, sources.keySet());
        } catch (RuntimeException error) {
            glDeleteProgram(program);
            throw error;
        } finally {
            for (int shader : compiled) glDeleteShader(shader);
        }
    }

    private static int compileShader(ShaderStage stage, SourceUnit unit) {
        int shader = glCreateShader(stage.glType());
        glShaderSource(shader, unit.source());
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new GlException(stage + " shader compile failed (" + unit.debugName() + "):\n" + log);
        }
        return shader;
    }

    private static void validateStages(EnumMap<ShaderStage, SourceUnit> sources) {
        if (sources.isEmpty()) throw new IllegalStateException("At least one shader stage is required");
        boolean compute = sources.containsKey(ShaderStage.COMPUTE);
        if (compute && sources.size() != 1) {
            throw new IllegalStateException("A compute program cannot contain graphics stages");
        }
        if (!compute && !sources.containsKey(ShaderStage.VERTEX)) {
            throw new IllegalStateException("A graphics program requires a vertex stage");
        }
    }

    private static String readResource(Class<?> anchor, String path) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(path, "path");
        try (InputStream inputStream = anchor.getResourceAsStream(path)) {
            if (inputStream == null) throw new GlException("Shader resource not found: " + path);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GlException("Failed to read shader resource: " + path, e);
        }
    }

    private record SourceUnit(String source, String debugName) {
        private SourceUnit {
            source = Objects.requireNonNull(source, "source");
            debugName = Objects.requireNonNull(debugName, "debugName");
            if (source.isBlank()) throw new IllegalArgumentException("shader source is blank: " + debugName);
        }
    }

    public static final class Builder {
        private final EnumMap<ShaderStage, SourceUnit> sources = new EnumMap<>(ShaderStage.class);

        private Builder() {
        }

        public Builder stage(ShaderStage stage, String source) {
            return stage(stage, source, stage.name().toLowerCase() + " source");
        }

        public Builder stage(ShaderStage stage, String source, String debugName) {
            Objects.requireNonNull(stage, "stage");
            SourceUnit previous = sources.putIfAbsent(stage, new SourceUnit(source, debugName));
            if (previous != null) throw new IllegalStateException("Shader stage already defined: " + stage);
            return this;
        }

        public Builder resource(Class<?> anchor, ShaderStage stage, String path) {
            return stage(stage, readResource(anchor, path), path);
        }

        public ShaderProgram link() {
            return ShaderProgram.link(new EnumMap<>(sources));
        }
    }
}
