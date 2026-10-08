package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.PassResources;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import static org.lwjgl.opengl.GL46.*;

/** Demo-only photometric control. Scene/history policy is unchanged; selected lamp q is omitted on the GPU. */
public final class VolumetricTemporalCounterfactual implements AutoCloseable {
    private final RenderPipeline pipeline;
    private final VolumetricPassBuilder volume;
    private final long selectedStableId;
    private final ShaderProgram source, reactive;
    private final GlBuffer reactiveWords;
    private final ScreenQuad quad;
    private final ByteBuffer words;
    private final Path candidatePolicy;
    private final int width, height;
    private int frame = -1;

    public VolumetricTemporalCounterfactual(RenderPipeline pipeline, Scene scene, int selectedSceneLight,
                                           Path candidateDirectory, boolean taa) {
        if (!Boolean.getBoolean("haikalat.glReadback"))
            throw new IllegalStateException("temporal counterfactual requires explicit diagnostic readback");
        this.pipeline = pipeline;
        volume = pipeline.activeGenerationForTest().volumetric;
        selectedStableId = scene.lightEntries().get(selectedSceneLight).stableId();
        candidatePolicy = candidateDirectory.resolve("policy");
        width = pipeline.graph().width(); height = pipeline.graph().height();
        ShaderProgram sourceProgram = null, reactiveProgram = null; GlBuffer storage = null; ScreenQuad screen = null;
        try {
            String original = VolumetricPassBuilder.shaderSource("source.comp", false);
            String declaration = "vec3 volumeLightRadiance(uint index,vec3 world,vec3 V,float depth) {";
            if (original.indexOf(declaration) < 0 || original.indexOf(declaration) != original.lastIndexOf(declaration))
                throw new IllegalStateException("cannot locate the single volume source light evaluator");
            sourceProgram = ShaderProgram.fromComputeSource(original.replace(declaration,
                    "uniform int uOmittedVolumeLight;\n" + declaration +
                    "\n    if (int(index)==uOmittedVolumeLight) return vec3(0.0);"));
            if (taa) {
                screen = new ScreenQuad();
                storage = new GlBuffer(GL_SHADER_STORAGE_BUFFER, GL_DYNAMIC_DRAW);
                storage.allocateStorage(((long) width * height + 3) / 4 * 4, GL_DYNAMIC_STORAGE_BIT);
                reactiveProgram = ShaderProgram.fromSources("""
                        #version 460 core
                        layout(location=0)in vec2 aPos;
                        void main(){gl_Position=vec4(aPos,0.0,1.0);}
                        """, """
                        #version 460 core
                        layout(std430,binding=9)readonly buffer ReactiveReplay{uint words[];};
                        uniform int uNativeWidth;
                        layout(location=0)out float outReactive;
                        void main(){ivec2 p=ivec2(gl_FragCoord.xy);int i=p.x+p.y*uNativeWidth;
                            outReactive=float((words[i/4]>>uint(8*(i%4)))&255u)/255.0;}
                        """);
            }
            source = sourceProgram; reactive = reactiveProgram; reactiveWords = storage; quad = screen;
            words = taa ? ByteBuffer.allocateDirect(Math.multiplyExact((width * height + 3) / 4, 4))
                    .order(ByteOrder.nativeOrder()) : null;
            volume.diagnosticRecorders(this::recordSource, taa ? this::recordReactive : null);
        } catch (RuntimeException | Error failure) {
            for (AutoCloseable owned : new AutoCloseable[]{reactiveProgram, storage, screen, sourceProgram})
                if (owned != null) try { owned.close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void frame(int frame) throws IOException {
        this.frame = frame;
        if (words != null) {
            byte[] values = readReactive(candidatePolicy.resolve(name(frame) + ".r8.gz"), width, height);
            words.clear();
            for (int i = 0; i < values.length; i += 4) {
                int word = 0;
                for (int b = 0; b < 4 && i + b < values.length; b++) word |= (values[i + b] & 255) << (8 * b);
                words.putInt(word);
            }
            words.flip();
        }
    }

    private void recordSource(PassResources inputs, CommandBuffer cmd) {
        if (frame < 0) throw new IllegalStateException("counterfactual frame was not selected");
        int index = pipeline.activeGenerationForTest().clusteredLightingBinder.stagedTable().frameLightIndex(selectedStableId);
        if (index < 0) throw new IllegalStateException("selected counterfactual lamp is absent from the frame light table");
        volume.diagnosticOmittedLight(index);
        cmd.bindShader(source).setUniformInt(source, "uOmittedVolumeLight", index);
        volume.recordReferenceSource(inputs, cmd, source, volume.resources().source());
        if (words != null) cmd.uploadBufferRegion(reactiveWords, 0, words);
    }

    private void recordReactive(CommandBuffer cmd) {
        cmd.bindShader(reactive).setUniformInt(reactive, "uNativeWidth", width)
                .bindStorageBuffer(9, reactiveWords, 0, words.remaining())
                .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT)
                .enableBlend(false).enableDepthTest(false).depthMask(false).enableCullFace(false)
                .bindVertexArray(quad.id()).drawArrays(GL_TRIANGLES, 0, 6);
    }

    /** Save exact policy inputs for every successful frame, including all warm-up frames. */
    public static void savePolicy(RenderPipeline pipeline, int frame, Path directory, boolean taa) throws IOException {
        Path policy = directory.resolve("policy"); Files.createDirectories(policy);
        Files.write(policy.resolve(name(frame) + ".bin"), policyBytes(pipeline));
        if (taa) {
            byte[] values = reactiveBytes(pipeline);
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(policy.resolve(name(frame) + ".r8.gz"))))) {
                out.writeInt(pipeline.graph().width()); out.writeInt(pipeline.graph().height()); out.write(values);
            }
        }
    }

    public void verifyPolicy() throws IOException {
        byte[] expected = Files.readAllBytes(candidatePolicy.resolve(name(frame) + ".bin"));
        if (!Arrays.equals(expected, policyBytes(pipeline)))
            throw new IllegalStateException("counterfactual history policy differs from candidate at frame " + frame);
        if (words != null && !Arrays.equals(readReactive(candidatePolicy.resolve(name(frame) + ".r8.gz"), width, height), reactiveBytes(pipeline)))
            throw new IllegalStateException("counterfactual TAA reactive replay differs from candidate at frame " + frame);
    }

    private static byte[] policyBytes(RenderPipeline pipeline) throws IOException {
        var volume = pipeline.activeGenerationForTest().volumetric;
        var plan = volume.historyPlan(); var frame = volume.frameState().previous();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(plan.count()); out.writeInt(plan.reason().ordinal()); out.writeBoolean(plan.shadowRefit());
            out.writeLong(frame.sequence()); out.writeFloat(frame.timeSeconds());
            out.writeFloat(frame.samplePhase()); out.writeFloat(frame.samplePhaseX()); out.writeFloat(frame.samplePhaseY());
            ByteBuffer domains = plan.domains().duplicate(); byte[] payload = new byte[domains.remaining()]; domains.get(payload);
            out.write(payload);
        }
        return bytes.toByteArray();
    }

    private static byte[] reactiveBytes(RenderPipeline pipeline) {
        float[] rgba = pipeline.captureVolumetricReactiveRgbaFloat(); byte[] bytes = new byte[rgba.length / 4];
        for (int i = 0; i < bytes.length; i++) {
            float value = rgba[i * 4]; int quantized = Math.round(value * 255);
            if (!Float.isFinite(value) || quantized < 0 || quantized > 255 || Math.abs(value - quantized / 255f) > 1e-6f)
                throw new IllegalStateException("temporal reactive target is not native R8");
            bytes[i] = (byte) quantized;
        }
        return bytes;
    }

    private static byte[] readReactive(Path file, int width, int height) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != width || in.readInt() != height) throw new IOException("reactive replay extent mismatch");
            byte[] bytes = in.readNBytes(Math.multiplyExact(width, height));
            if (bytes.length != width * height || in.read() != -1) throw new IOException("reactive replay payload size mismatch");
            return bytes;
        }
    }

    private static String name(int frame) { return String.format(java.util.Locale.ROOT, "%04d", frame); }
    @Override public void close() {
        volume.diagnosticRecorders(null, null);
        volume.diagnosticOmittedLight(-1);
        if (reactive != null) reactive.close(); if (reactiveWords != null) reactiveWords.close();
        if (quad != null) quad.close(); source.close();
    }
}
