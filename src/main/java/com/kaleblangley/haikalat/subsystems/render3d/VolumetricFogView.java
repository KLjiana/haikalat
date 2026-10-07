package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Frame-scoped borrowed fog input for a controlled HDR recorder. Contains no transferable native IDs. */
public final class VolumetricFogView {
    public static final String SHADER_CONTRACT = "HAIKALAT_VOLUME_FOG_CONTRACT";
    private final long generationId, frameSequence;
    private final BiConsumer<CommandBuffer, ShaderProgram> binder;
    private final String softParticleDepthName;
    private final java.util.function.Consumer<java.util.function.Consumer<CommandBuffer>> reactiveRegistration;
    VolumetricFogView(long generationId, long frameSequence, String softParticleDepthName,
                      BiConsumer<CommandBuffer, ShaderProgram> binder,
                      java.util.function.Consumer<java.util.function.Consumer<CommandBuffer>> reactiveRegistration) {
        this.generationId=generationId; this.frameSequence=frameSequence; this.binder=binder;
        this.softParticleDepthName=softParticleDepthName;
        this.reactiveRegistration=reactiveRegistration;
    }
    public long generationId() { return generationId; }
    public long frameSequence() { return frameSequence; }
    /** Graph logical input; its texture is never the depth attachment of the HDR VFX target. */
    public String softParticleDepthName() { return softParticleDepthName; }
    public void bind(CommandBuffer commands, ShaderProgram shader) {
        binder.accept(Objects.requireNonNull(commands),Objects.requireNonNull(shader));
    }
    /** Registers a frozen, own-depth mask replay; called while recording this frame's HDR color. */
    public void recordReactive(java.util.function.Consumer<CommandBuffer> recorder) {
        reactiveRegistration.accept(Objects.requireNonNull(recorder));
    }
    public static String fragmentPrelude() {
        return "#define HAIKALAT_VOLUME_FOG\n"+VolumetricPassBuilder.resource("volume-common.glsl")+"\n"
                +VolumetricPassBuilder.resource("volume-query.glsl")+"\n"
                +VolumetricPassBuilder.resource("volume-reactive.glsl")+"\n"
                +finePrelude()+"\n"
                +VolumetricPassBuilder.resource("volume-surface.glsl");
    }
    static String fineUniform(String name) { return "uFine"+name.substring(1); }
    private static String finePrelude() {
        // Vertex stages already own SSBO 0 and 7..12. Fragment refinement reads
        // the same light table at 13 and uses the otherwise free 4/5/6 bindings.
        // Shadow samplers and the shadow UBO are shared with the built-in PBR stage.
        String source=VolumetricPassBuilder.resource("volume-lighting.glsl")
                .replace("binding=0)","binding=13)")
                .replace("binding=7) buffer VolumeDiagnostics","binding=5) buffer VolumeDiagnostics")
                +"\n"+VolumetricPassBuilder.resource("volume-noise.glsl")
                +"\n"+VolumetricPassBuilder.resource("volume-medium.glsl")
                +"\n"+VolumetricPassBuilder.resource("volume-fine.glsl")
                +"\n"+VolumetricPassBuilder.resource("volume-native-reactive.glsl");
        var shared=java.util.Set.of("uShadowMap","uPointShadowMap","uSpotShadowMap","uPointShadowMeta",
                "uPointSlotMatrices","uPointFaceRects","uSpotShadowMeta","uSpotSlotMatrices","uSpotTileRects",
                "uShadowQualityMeta","uVolumePrefix","uVolumeSource","uVolumeSurfaceGuideEnabled","uFineOmittedLight",
                "uVolumeCurrentSource","uVolumeCurrentSourceEnabled");
        return java.util.regex.Pattern.compile("\\b(u[A-Z][A-Za-z0-9_]*|LightRecord|VolumeHint|FogVolume|FogVolumes|fogVolumes|diagnostics|LightTableBlock|ClusterHeadersBlock|ClusterIndicesBlock|ClusterParametersBlock|LightVolumeHintsBlock|VolumeDiagnostics)\\b")
                .matcher(source).replaceAll(match->{
                    String name=match.group();
                    if(shared.contains(name))return name;
                    return name.startsWith("u")?fineUniform(name):"Fine"+name;
                });
    }
}
