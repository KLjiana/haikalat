package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.subsystems.vfx.EffectSnapshot;
import com.kaleblangley.haikalat.subsystems.vfx.FlipbookConfig;
import com.kaleblangley.haikalat.subsystems.vfx.VfxBillboardMode;
import com.kaleblangley.haikalat.subsystems.vfx.VfxMaterial;
import com.kaleblangley.haikalat.subsystems.vfx.VfxUvRegion;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将纯 CPU {@link EffectSnapshot} 转换为受控 OpenGL 命令的 render3d 适配器。 */
public final class VfxRenderer implements AutoCloseable {
    private static final int MAT4_BYTES = 16 * Float.BYTES;
    private static final int COLOR_BYTES = 4 * Float.BYTES;

    private final ShaderProgram shader;
    private final Mesh quad;
    private final VfxTextureCache textureCache;
    private final Map<MeshData, Mesh> meshCache = new IdentityHashMap<>();
    private Vector3f[] ribbonSides = new Vector3f[0];
    private Vector3f[] ribbonStartOffsets = new Vector3f[0];
    private Vector3f[] ribbonEndOffsets = new Vector3f[0];
    private float[] ribbonStartUvs = new float[0];
    private float[] ribbonEndUvs = new float[0];
    private Statistics lastStatistics = Statistics.empty();
    private boolean closed;

    public VfxRenderer() {
        ShaderProgram createdShader = null;
        Mesh createdQuad = null;
        VfxTextureCache createdTextureCache = null;
        try {
            createdShader = ShaderProgram.fromResource(VfxRenderer.class,
                    "/shaders/render3d/vfx/vfx.vert", "/shaders/render3d/vfx/vfx.frag");
            createdQuad = Mesh.from(BuiltinMeshData.texturedQuad("vfx-quad"));
            createdTextureCache = new VfxTextureCache();
        } catch (RuntimeException | Error failure) {
            closeSuppressing(createdTextureCache, failure);
            closeSuppressing(createdQuad, failure);
            closeSuppressing(createdShader, failure);
            throw failure;
        }
        shader = createdShader;
        quad = createdQuad;
        textureCache = createdTextureCache;
    }

    /**
     * 按快照给出的远到近顺序记录 alpha primitive。
     * 调用方负责 framebuffer、viewport、clear 和最终 present。
     */
    public Statistics record(CommandBuffer commands, EffectSnapshot snapshot,
                             Matrix4fc projection, Matrix4fc view) {
        return record(commands, snapshot, projection, view, 0, 1, 1);
    }

    /** Records VFX with an optional single-sample scene depth texture for soft intersections. */
    public Statistics record(CommandBuffer commands, EffectSnapshot snapshot,
                             Matrix4fc projection, Matrix4fc view,
                             int sceneDepthTexture, int viewportWidth, int viewportHeight) {
        ensureOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(view, "view");
        if (sceneDepthTexture < 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("depth texture and viewport must be valid");
        }

        Matrix4f projectionView = new Matrix4f(projection).mul(view);
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Matrix4f inverseView = new Matrix4f(view).invert();
        Vector3f cameraPosition = inverseView.getTranslation(new Vector3f());
        Matrix4f inverseViewRotation = new Matrix4f(inverseView);
        inverseViewRotation.m30(0.0f).m31(0.0f).m32(0.0f).m33(1.0f);
        Matrix4f model = new Matrix4f();
        Matrix4f mvp = new Matrix4f();
        List<EffectSnapshot.RibbonSegment> ribbonSegments = snapshot.ribbonSegments();
        prepareRibbonGeometry(ribbonSegments, cameraPosition);
        VfxMaterial boundMaterial = null;
        int particles = 0;
        int ribbons = 0;
        int decals = 0;
        int meshes = 0;
        int materialSwitches = 0;
        int alphaDraws = 0;
        int additiveDraws = 0;
        int softParticleDraws = 0;

        commands.bindShader(shader)
                .bindMesh(quad)
                .setUniformMat4(shader, "uProjectionView", projectionView)
                .enableCullFace(false);
        Mesh boundMesh = quad;
        for (EffectSnapshot.Primitive primitive : snapshot.transparentDrawOrder()) {
            VfxMaterial material = snapshot.visuals().material(primitive.kind());
            Mesh drawMesh = quad;
            float ribbonStartUv = 0.0f;
            float ribbonEndUv = 1.0f;
            Vector3f ribbonStart = null;
            Vector3f ribbonEnd = null;
            Vector3f ribbonStartOffset = null;
            Vector3f ribbonEndOffset = null;
            switch (primitive) {
                case EffectSnapshot.ParticleSprite particle -> {
                    particleModel(particle, material, view, inverseViewRotation,
                            cameraPosition, model);
                    particles++;
                }
                case EffectSnapshot.RibbonSegment ribbon -> {
                    int index = ribbonIndex(ribbonSegments, ribbon.sequence());
                    model.identity();
                    ribbonStart = ribbon.start();
                    ribbonEnd = ribbon.end();
                    ribbonStartOffset = ribbonStartOffsets[index];
                    ribbonEndOffset = ribbonEndOffsets[index];
                    ribbonStartUv = ribbonStartUvs[index];
                    ribbonEndUv = ribbonEndUvs[index];
                    ribbons++;
                }
                case EffectSnapshot.DecalInstance decal -> {
                    decalModel(decal, model);
                    decals++;
                }
                case EffectSnapshot.MeshInstance mesh -> {
                    model.set(mesh.model());
                    drawMesh = meshFor(mesh.meshData());
                    meshes++;
                }
            }
            if (drawMesh != boundMesh) {
                commands.bindMesh(drawMesh);
                boundMesh = drawMesh;
            }
            if (!material.equals(boundMaterial)) {
                Texture2D texture = textureCache.get(material);
                VfxUvRegion uv = material.uvRegion();
                commands.materialState(material.blendMode(), true)
                        .bindTexture(0, texture)
                        .setUniformInt(shader, "uTexture", 0)
                        .setUniformInt(shader, "uMaskMode", material.maskMode().ordinal())
                        .setUniformInt(shader, "uAdditive",
                                material.blendMode() == com.kaleblangley.haikalat.core.BlendMode.ADDITIVE
                                        ? 1 : 0)
                        .setUniformFloat(shader, "uEmissiveIntensity", material.emissiveIntensity())
                        .setUniformFloat(shader, "uAlphaCutoff", material.alphaCutoff())
                        .setUniformFloat(shader, "uSoftParticleDistance",
                                sceneDepthTexture == 0 ? 0.0f : material.softParticleDistance())
                        .setUniformInt(shader, "uDepthEnabled", sceneDepthTexture == 0 ? 0 : 1)
                        .setUniformVec4(shader, "uUvRegion", uvVector(uv))
                        .setUniformVec4(shader, "uNextUvRegion", uvVector(uv))
                        .setUniformFloat(shader, "uFlipbookBlend", 0.0f);
                if (sceneDepthTexture != 0) {
                    commands.bindTexture(1, sceneDepthTexture)
                            .setUniformInt(shader, "uSceneDepth", 1)
                            .setUniformVec2(shader, "uViewportSize", viewportWidth, viewportHeight)
                            .setUniformMat4(shader, "uInverseProjection", inverseProjection);
                }
                boundMaterial = material;
                materialSwitches++;
            }
            VfxUvRegion currentUv = material.uvRegion();
            VfxUvRegion nextUv = currentUv;
            float flipbookBlend = 0.0f;
            if (primitive instanceof EffectSnapshot.ParticleSprite particle
                    && material.flipbook().isPresent()) {
                FlipbookConfig flipbook = material.flipbook().orElseThrow();
                FlipbookConfig.FrameSample sample = flipbook.sample(
                        particle.ageSeconds(), particle.flipbookStartFrame());
                currentUv = flipbook.frameRegion(sample.currentFrame(), material.uvRegion());
                nextUv = flipbook.frameRegion(sample.nextFrame(), material.uvRegion());
                flipbookBlend = sample.blend();
            }
            if (material.blendMode() == com.kaleblangley.haikalat.core.BlendMode.ADDITIVE) {
                additiveDraws++;
            } else {
                alphaDraws++;
            }
            if (sceneDepthTexture != 0 && material.softParticleDistance() > 0.0f) {
                softParticleDraws++;
            }
            projectionView.mul(model, mvp);
            commands.setUniformMat4(shader, "uMvp", mvp)
                    .setUniformVec4(shader, "uColor", primitive.color())
                    .setUniformVec4(shader, "uUvRegion", uvVector(currentUv))
                    .setUniformVec4(shader, "uNextUvRegion", uvVector(nextUv))
                    .setUniformFloat(shader, "uFlipbookBlend", flipbookBlend)
                    .setUniformInt(shader, "uRibbon",
                            primitive instanceof EffectSnapshot.RibbonSegment ? 1 : 0);
            if (ribbonStart != null) {
                commands.setUniformVec3(shader, "uRibbonStart", ribbonStart)
                        .setUniformVec3(shader, "uRibbonEnd", ribbonEnd)
                        .setUniformVec3(shader, "uRibbonStartOffset", ribbonStartOffset)
                        .setUniformVec3(shader, "uRibbonEndOffset", ribbonEndOffset);
            }
            commands.setUniformVec2(shader, "uRibbonUvRange", ribbonStartUv, ribbonEndUv)
                    .setUniformVec2(shader, "uRibbonWidths",
                            primitive instanceof EffectSnapshot.RibbonSegment ribbon
                                    ? ribbon.startWidth() : 1.0f,
                            primitive instanceof EffectSnapshot.RibbonSegment ribbon
                                    ? ribbon.endWidth() : 1.0f)
                    .drawMesh(quad);
        }
        lastStatistics = new Statistics(snapshot.primitiveCount(), particles, ribbons, decals, meshes,
                (long) snapshot.primitiveCount() * (MAT4_BYTES + COLOR_BYTES),
                materialSwitches, materialSwitches, alphaDraws, additiveDraws, softParticleDraws);
        return lastStatistics;
    }

    public Statistics statistics() {
        return lastStatistics;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        for (Mesh mesh : meshCache.values()) {
            failure = closeCollecting(mesh, failure);
        }
        meshCache.clear();
        failure = closeCollecting(textureCache, failure);
        failure = closeCollecting(quad, failure);
        failure = closeCollecting(shader, failure);
        closed = true;
        if (failure != null) throw failure;
    }

    private static void particleModel(EffectSnapshot.ParticleSprite particle,
                                      VfxMaterial material, Matrix4fc view,
                                      Matrix4f inverseViewRotation, Vector3f cameraPosition,
                                      Matrix4f out) {
        float rotation = particle.rotationRadians();
        float stretch = 1.0f;
        if (material.billboardMode() == VfxBillboardMode.VELOCITY_ALIGNED
                && particle.velocity().lengthSquared() > 1.0e-12f) {
            Vector3f viewVelocity = view.transformDirection(particle.velocity(), new Vector3f());
            if (viewVelocity.x * viewVelocity.x + viewVelocity.y * viewVelocity.y > 1.0e-12f) {
                rotation += (float) Math.atan2(viewVelocity.y, viewVelocity.x);
                stretch = Math.min(material.maximumStretch(),
                        1.0f + particle.velocity().length() * material.velocityStretch());
            }
        }
        if (material.billboardMode() == VfxBillboardMode.Y_AXIS_LOCKED) {
            Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
            Vector3f facing = cameraPosition.sub(particle.center(), new Vector3f());
            facing.y = 0.0f;
            if (facing.lengthSquared() <= 1.0e-12f) facing.set(0.0f, 0.0f, 1.0f);
            else facing.normalize();
            Vector3f right = up.cross(facing, new Vector3f()).normalize();
            setBasis(out, particle.center(), right, up, facing,
                    particle.size() * stretch, particle.size(), 1.0f);
            out.rotateZ(rotation);
            return;
        }
        out.identity().translation(particle.center())
                .mul(inverseViewRotation)
                .rotateZ(rotation)
                .scale(particle.size() * stretch, particle.size(), particle.size());
    }

    private void prepareRibbonGeometry(List<EffectSnapshot.RibbonSegment> segments,
                                       Vector3f cameraPosition) {
        int size = segments.size();
        ensureRibbonCapacity(size);
        for (int index = 0; index < size; index++) {
            EffectSnapshot.RibbonSegment segment = segments.get(index);
            ribbonSide(segment, cameraPosition, ribbonSides[index]);
            ribbonStartOffsets[index].set(ribbonSides[index]).mul(segment.startWidth() * 0.5f);
            ribbonEndOffsets[index].set(ribbonSides[index]).mul(segment.endWidth() * 0.5f);
        }
        Vector3f join = new Vector3f();
        for (int index = 1; index < size; index++) {
            EffectSnapshot.RibbonSegment previous = segments.get(index - 1);
            EffectSnapshot.RibbonSegment next = segments.get(index);
            Vector3f joint = previous.end();
            Vector3f nextStart = next.start();
            if (joint.distanceSquared(nextStart) > 1.0e-10f) continue;
            float width = Math.max(previous.endWidth(), next.startWidth());
            ribbonJoinOffset(ribbonSides[index - 1], ribbonSides[index], width, join);
            ribbonEndOffsets[index - 1].set(join);
            ribbonStartOffsets[index].set(join);
        }

        float totalLength = 0.0f;
        for (EffectSnapshot.RibbonSegment segment : segments) {
            totalLength += segment.start().distance(segment.end());
        }
        float accumulatedLength = 0.0f;
        for (int index = 0; index < size; index++) {
            EffectSnapshot.RibbonSegment segment = segments.get(index);
            float length = segment.start().distance(segment.end());
            ribbonStartUvs[index] = ribbonTextureProgress(accumulatedLength, totalLength);
            accumulatedLength += length;
            ribbonEndUvs[index] = ribbonTextureProgress(accumulatedLength, totalLength);
        }
    }

    static Vector3f ribbonJoinOffset(Vector3f previousSide, Vector3f nextSide,
                                     float width, Vector3f destination) {
        float halfWidth = width * 0.5f;
        Vector3f result = destination.set(previousSide).add(nextSide);
        if (result.lengthSquared() <= 1.0e-10f) {
            return result.set(nextSide).mul(halfWidth);
        }
        result.normalize();
        float projection = Math.abs(result.dot(nextSide));
        float miterLength = Math.min(width, halfWidth / Math.max(1.0e-4f, projection));
        return result.mul(miterLength);
    }

    static float ribbonProgress(float distance, float totalLength) {
        if (totalLength <= 1.0e-6f) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, distance / totalLength));
    }

    static float ribbonTextureProgress(float distance, float totalLength) {
        return 1.0f - ribbonProgress(distance, totalLength);
    }

    private void ensureRibbonCapacity(int size) {
        if (ribbonStartOffsets.length >= size) return;
        int capacity = Math.max(size, Math.max(16, ribbonStartOffsets.length * 2));
        ribbonSides = vectorArray(capacity);
        ribbonStartOffsets = vectorArray(capacity);
        ribbonEndOffsets = vectorArray(capacity);
        ribbonStartUvs = new float[capacity];
        ribbonEndUvs = new float[capacity];
    }

    private static Vector3f[] vectorArray(int size) {
        Vector3f[] values = new Vector3f[size];
        for (int index = 0; index < size; index++) values[index] = new Vector3f();
        return values;
    }

    private static int ribbonIndex(List<EffectSnapshot.RibbonSegment> segments, long sequence) {
        int low = 0;
        int high = segments.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            long candidate = segments.get(middle).sequence();
            if (candidate < sequence) low = middle + 1;
            else if (candidate > sequence) high = middle - 1;
            else return middle;
        }
        throw new IllegalStateException("Ribbon draw order contains an unknown segment");
    }

    private static void ribbonSide(EffectSnapshot.RibbonSegment ribbon,
                                   Vector3f cameraPosition, Vector3f out) {
        Vector3f start = ribbon.start();
        Vector3f end = ribbon.end();
        Vector3f direction = end.sub(start, out).normalize();
        Vector3f center = start.add(end, new Vector3f()).mul(0.5f);
        Vector3f toCamera = cameraPosition.sub(center, new Vector3f());
        direction.cross(toCamera, out);
        if (out.lengthSquared() <= 1.0e-12f) {
            Vector3f fallback = Math.abs(direction.y) < 0.99f
                    ? new Vector3f(0.0f, 1.0f, 0.0f) : new Vector3f(1.0f, 0.0f, 0.0f);
            direction.cross(fallback, out);
        }
        out.normalize();
    }

    private static void decalModel(EffectSnapshot.DecalInstance decal, Matrix4f out) {
        Vector3f normal = decal.normal();
        Quaternionf orientation = new Quaternionf().rotationTo(
                new Vector3f(0.0f, 0.0f, 1.0f), normal);
        out.identity().translation(new Vector3f(decal.center()).fma(0.004f, normal))
                .rotate(orientation)
                .rotateZ(decal.rotationRadians())
                .scale(decal.size().x, decal.size().y, 1.0f);
    }

    private Mesh meshFor(MeshData meshData) {
        Mesh cached = meshCache.get(meshData);
        if (cached != null) return cached;
        Mesh created = Mesh.from(meshData);
        meshCache.put(meshData, created);
        return created;
    }

    private static Vector4f uvVector(VfxUvRegion region) {
        return new Vector4f(region.minimumU(), region.minimumV(),
                region.maximumU(), region.maximumV());
    }

    private static void setBasis(Matrix4f out, Vector3f translation,
                                 Vector3f x, Vector3f y, Vector3f z,
                                 float scaleX, float scaleY, float scaleZ) {
        out.identity()
                .m00(x.x * scaleX).m01(x.y * scaleX).m02(x.z * scaleX)
                .m10(y.x * scaleY).m11(y.y * scaleY).m12(y.z * scaleY)
                .m20(z.x * scaleZ).m21(z.y * scaleZ).m22(z.z * scaleZ)
                .m30(translation.x).m31(translation.y).m32(translation.z);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("VfxRenderer is closed");
    }

    private static void closeSuppressing(AutoCloseable value, Throwable failure) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static RuntimeException closeCollecting(AutoCloseable value, RuntimeException failure) {
        try {
            value.close();
        } catch (Exception closeFailure) {
            RuntimeException wrapped = closeFailure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Failed to close VFX render resource", closeFailure);
            if (failure == null) return wrapped;
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    public record Statistics(int drawCalls, int particles, int ribbonSegments,
                             int decals, int meshes, long uniformPayloadBytes,
                             int textureBinds, int materialSwitches,
                             int alphaDraws, int additiveDraws, int softParticleDraws) {
        public Statistics(int drawCalls, int particles, int ribbonSegments,
                          int decals, long uniformPayloadBytes,
                          int textureBinds, int materialSwitches,
                          int alphaDraws, int additiveDraws, int softParticleDraws) {
            this(drawCalls, particles, ribbonSegments, decals, 0, uniformPayloadBytes,
                    textureBinds, materialSwitches, alphaDraws, additiveDraws,
                    softParticleDraws);
        }

        public Statistics(int drawCalls, int particles, int ribbonSegments,
                          int decals, long uniformPayloadBytes) {
            this(drawCalls, particles, ribbonSegments, decals, 0, uniformPayloadBytes,
                    0, 0, drawCalls, 0, 0);
        }

        private static Statistics empty() {
            return new Statistics(0, 0, 0, 0, 0, 0L,
                    0, 0, 0, 0, 0);
        }
    }
}
