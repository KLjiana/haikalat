package com.kaleblangley.haikalat.subsystems.render3d.pbr;

import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.FrontFace;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.material.MaterialInstance;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.SceneDrawBinding;
import com.kaleblangley.haikalat.subsystems.render3d.scene.SceneFrame;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;

/**
 * Records the bounded single-layer refraction stream.
 *
 * <p>The graph copies the resolved scene colour into a separate texture before
 * this pass runs.  Refraction materials are excluded from the ordinary forward
 * queue and are recorded here in the existing back-to-front transparent order;
 * every draw samples the snapshot, never the colour attachment this pass
 * writes.  Consequently overlapping glass never refracts other glass: the pass
 * is a single transmitted layer by construction.</p>
 *
 * <p>The snapshot colour texture contract is the RenderGraph colour-attachment
 * contract: linear filtering, clamp-to-edge, one sample.</p>
 */
public final class RefractionPass {
    /** Graph pass that copies the resolved scene colour into the snapshot. */
    public static final String SOURCE_COPY_PASS = "RefractionSourceCopy";
    /** Graph pass that records all active refraction materials. */
    public static final String PASS_NAME = "RefractionPass";
    /** Single-sample snapshot of the resolved scene colour. */
    public static final String SOURCE_TEXTURE = "refractionSceneColor";
    /** Fragment texture unit reserved for the snapshot. */
    public static final int SOURCE_TEXTURE_UNIT = 5;

    private static final Matrix4f MODEL_SCRATCH = new Matrix4f();

    private RefractionPass() {
    }

    /** True when at least one scene material enables the refraction path. */
    public static boolean presentIn(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        for (MeshRenderer renderer : scene.renderers()) {
            Material material = renderer.material().material();
            if (material.refraction().active()) return true;
        }
        return false;
    }

    /**
     * Copies {@code source} into the single-sample snapshot target, resolving
     * multisampled colour when needed.  Source and target extents must match.
     */
    public static void recordSourceCopy(CommandBuffer cmd, Framebuffer source, Framebuffer target) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.width() != target.width() || source.height() != target.height()) {
            throw new IllegalArgumentException("refraction snapshot extent must match its source");
        }
        cmd.blitColor(source, target)
                .bindFramebuffer(target)
                .viewport(0, 0, target.width(), target.height());
    }

    /**
     * Records every refraction-enabled renderer of {@code frame} into the
     * currently bound refraction target.
     *
     * @param cmd           GPU-owner command buffer inside the refraction pass
     * @param frame         sorted forward snapshot for this frame
     * @param camera        frame camera (world position for the view vector)
     * @param sourceTexture single-sample scene-colour snapshot
     * @param width         target width in pixels
     * @param height        target height in pixels
     */
    public static void record(CommandBuffer cmd, SceneFrame frame, Camera camera,
                              int sourceTexture, int width, int height) {
        Objects.requireNonNull(cmd, "cmd");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(camera, "camera");
        if (sourceTexture <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "refraction requires a valid snapshot and positive extent");
        }
        Vector3f cameraPosition = camera.position();
        cmd.depthFunc(GL_LEQUAL)
                .enableDepthTest(true)
                .depthMask(false)
                .enableBlend(true);
        ShaderProgram boundShader = null;
        MaterialInstance boundMaterial = null;
        Mesh boundMesh = null;
        boolean frontFaceBound = false;
        boolean boundMirrored = false;
        for (int queueIndex = 0; queueIndex < frame.forwardCount(); queueIndex++) {
            int entry = frame.forwardEntry(queueIndex);
            MeshRenderer renderer = frame.renderer(entry);
            MaterialInstance material = renderer.material();
            Material template = material.material();
            if (!template.refraction().active()) continue;
            ShaderProgram shader = template.shader();
            Matrix4f model = frame.copyModel(entry, MODEL_SCRATCH);
            boolean mirrored = frame.mirrored(entry);
            if (!frontFaceBound || mirrored != boundMirrored) {
                cmd.frontFace(mirrored ? FrontFace.CW : FrontFace.CCW);
                frontFaceBound = true;
                boundMirrored = mirrored;
            }
            if (material != boundMaterial) {
                material.bind(cmd);
                boundMaterial = material;
            }
            if (shader != boundShader) {
                cmd.bindTexture(SOURCE_TEXTURE_UNIT, sourceTexture)
                        .setUniformInt(shader, "uRefractionSource", SOURCE_TEXTURE_UNIT)
                        .setUniformVec2(shader, "uRefractionExtent", width, height);
                boundShader = shader;
            }
            cmd.setUniformMat4(shader, "uModel", model)
                    .setUniformVec3(shader, "uCameraPosition", cameraPosition);
            cmd.trySetUniformInt(shader, "uSkinningEnabled",
                            renderer.drawBinding().skinningEnabled() ? 1 : 0)
                    .trySetUniformInt(shader, "uMorphTargetCount",
                            renderer.drawBinding().morphTargetCount());
            SceneDrawBinding drawBinding = renderer.drawBinding();
            drawBinding.recordVertexDisplacement(cmd, shader, SceneDrawBinding.Pass.FORWARD);
            if (drawBinding != SceneDrawBinding.NONE) {
                drawBinding.record(cmd, shader, (int) frame.frameIndex(),
                        SceneDrawBinding.Pass.FORWARD);
            }
            if (renderer.mesh() != boundMesh) {
                cmd.bindMesh(renderer.mesh());
                boundMesh = renderer.mesh();
            }
            cmd.drawMesh(renderer.mesh());
        }
        // Restore the state later passes expect from the shared depth target.
        cmd.enableBlend(false).depthMask(true).enableDepthTest(true).frontFace(FrontFace.CCW);
    }
}
