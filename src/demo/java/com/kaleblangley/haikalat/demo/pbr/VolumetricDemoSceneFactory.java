package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.*;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.*;
import com.kaleblangley.haikalat.subsystems.vfx.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.nio.file.Path;
import java.util.*;

/** One geometry/light factory shared by the formal demo, quality and benchmark runners. */
final class VolumetricDemoSceneFactory {
    enum SceneId {
        FOREST_MORNING, TOWN_NIGHT, TAVERN_THRESHOLD, MEDIUM_LAB;
        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    static final class Bundle implements AutoCloseable {
        final SceneId id;
        final Scene scene;
        final List<AutoCloseable> owned;
        final int fastLightIndex;
        private final VfxVisualSet visuals;
        private final com.kaleblangley.haikalat.core.mesh.MeshData sparkMesh =
                com.kaleblangley.haikalat.core.mesh.BuiltinMeshData.texturedQuad("threshold-spark");

        Bundle(SceneId id, Scene scene, List<AutoCloseable> owned, int fastLightIndex) {
            this.id=id; this.scene=scene; this.owned=owned; this.fastLightIndex=fastLightIndex;
            var smoke=VfxMaterial.builder("threshold-smoke")
                    .texture(com.kaleblangley.haikalat.core.assets.AssetRef.of("/vfx/particles/kenney/particle_pack/smoke_03.png"))
                    .maskMode(VfxMaskMode.LUMINANCE).softParticleDistance(.35f).build();
            var sparks=VfxMaterial.builder("threshold-sparks").blendMode(BlendMode.ADDITIVE).build();
            visuals=new VfxVisualSet(smoke,smoke,smoke,sparks);
        }

        EffectSnapshot vfx(int frame) {
            if(id!=SceneId.TAVERN_THRESHOLD) return new EffectSnapshot(List.of(),List.of(),List.of(),List.of());
            var smoke=new EffectSnapshot.ParticleSprite(1,new Vector3f(-7.7f,1.55f,12.0f),1.5f,
                    0,new Vector4f(.13f,.14f,.15f,.2f),9);
            var spark=new EffectSnapshot.MeshInstance(2,
                    sparkMesh,
                    new Matrix4f().translation(-6.3f,1.55f+(frame%60)/100f,11.8f)
                            .rotateY((float)Math.PI/2).scale(.005f,.012f,.005f),
                    new Vector4f(3,.7f,.15f,.7f),4);
            return new EffectSnapshot(List.of(smoke),List.of(),List.of(),List.of(spark),
                    List.of(smoke,spark),visuals);
        }

        void event(int frame, RenderPipeline pipeline) {
            if(id!=SceneId.TAVERN_THRESHOLD) return;
            if(frame==0 || frame==120 || frame==180) {
                SceneLight l=scene.lights().get(fastLightIndex);
                scene.setLight(fastLightIndex,new SceneLight(l.type(),l.color(),frame==120?30:0,
                        l.direction(),l.position(),l.range(),l.innerConeRadians(),l.outerConeRadians(),l.castShadows()));
            }
            if(frame==240 && !pipeline.volumetricFog().localVolumes().isEmpty()) {
                var f=pipeline.volumetricFog();var volumes=new ArrayList<>(f.localVolumes());var old=volumes.getFirst();
                volumes.set(0,new LocalFogVolume(old.shape(),old.center().add(0,0,1),old.extent(),
                        old.extinction(),old.albedo(),old.emission(),old.falloff(),old.noiseScale(),old.noiseAmount()));
                pipeline.applyVolumetricFog(new VolumetricFogSettings(f.enabled(),f.fogDistance(),f.quality(),
                        f.globalMedium(),volumes,f.anisotropy(),f.history(),f.historyWeight(),f.noiseSeed(),f.wind()));
            }
        }

        @Override public void close() {
            RuntimeException failure=null;
            for(int i=owned.size()-1;i>=0;i--) try { owned.get(i).close(); }
            catch(Exception e) { var next=e instanceof RuntimeException r?r:new IllegalStateException(e);
                if(failure==null) failure=next;else failure.addSuppressed(next); }
            owned.clear();if(failure!=null)throw failure;
        }
    }

    private VolumetricDemoSceneFactory() { }

    static VisualProfile profile(SceneId id) {
        return VisualProfileCodec.load(Path.of("config/volumetric-fog/profiles",id.id()+".properties"),
                VisualProfile.outdoorCompatibility(OutdoorEnvironmentSettings.disabled()));
    }

    static Bundle create(SceneId id, ShaderProgram shader, PbrFallbackTextures fallback) {
        List<AutoCloseable> owned=new ArrayList<>();Scene scene;
        try {
            if(id==SceneId.FOREST_MORNING) {
                var meshes=new WoodlandMeshes();owned.add(meshes);var materials=new ArrayList<Material>();
                try { scene=WoodlandSceneFactory.create(meshes,shader,fallback,materials,false); }
                finally { owned.addAll(materials); }
            } else {
                var request=id==SceneId.MEDIUM_LAB?ClusteredDemoSceneFactory.Request.lab(8,4,1337)
                        :ClusteredDemoSceneFactory.Request.town();
                var base=id==SceneId.TAVERN_THRESHOLD?ClusteredDemoSceneFactory.createTavern(shader,fallback)
                        :id==SceneId.TOWN_NIGHT?ClusteredDemoSceneFactory.createVolumetricTown(shader,fallback)
                        :ClusteredDemoSceneFactory.create(request,shader,fallback);owned.add(base);scene=base.scene;
                if(id==SceneId.TAVERN_THRESHOLD) {
                    scene.camera().setPosition(new Vector3f(-4.8f,1.7f,12));scene.camera().setYaw(180);scene.camera().setPitch(0);
                    var texture=com.kaleblangley.haikalat.backend.texture.Texture2D.fromResource(
                            VolumetricDemoSceneFactory.class,"/vfx/particles/kenney/particle_pack/smoke_03.png",
                            com.kaleblangley.haikalat.backend.texture.TextureColorSpace.SRGB);owned.add(texture);
                    // This licensed mask stores smoke in RGB and has an opaque alpha channel.
                    // Remap the owned texture's alpha at creation; the source asset is unchanged.
                    org.lwjgl.opengl.GL45.glTextureParameteri(texture.id(),org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_A,
                            org.lwjgl.opengl.GL11.GL_RED);
                    Mesh quad=Mesh.from(com.kaleblangley.haikalat.core.mesh.TangentGenerator.generate(
                            com.kaleblangley.haikalat.core.mesh.BuiltinMeshData.texturedQuad("threshold-alpha-smoke")).mesh());owned.add(quad);
                    var properties=new PbrMaterialProperties(new Vector4f(.15f,.15f,.16f,.18f),0,.9f,1,1,new Vector3f(),Map.of());
                    Material alpha=PbrMaterials.createWithBindings(shader,properties,Map.of(
                            com.kaleblangley.haikalat.core.assets.PbrTextureRole.BASE_COLOR,new PbrTextureBinding(texture,fallback.sampler())),fallback,CullMode.NONE,
                            false,false,0,BlendMode.ALPHA);owned.add(alpha);
                    scene.add(SceneObject.fixed(quad,alpha,new Matrix4f().translation(-7.3f,1.55f,12)
                            .rotateY((float)Math.PI/2).scale(1.6f,2.6f,1),false));
                }
                if(id==SceneId.MEDIUM_LAB) {
                    Mesh wall=ClusteredDemoSceneFactory.box("medium-lab-occluder");owned.add(wall);
                    Material black=PbrMaterials.createWithBindings(shader,new PbrMaterialProperties(new Vector4f(.015f,.015f,.015f,1),
                            0,1,1,1,new Vector3f(),Map.of()),Map.of(),fallback,CullMode.BACK,false,false,0,BlendMode.OPAQUE);
                    owned.add(black);scene.add(SceneObject.fixed(wall,black,new Matrix4f().translation(1,2,-5).scale(.3f,2,2),true));
                    Scene unshadowed=scene;scene=new Scene(unshadowed.camera());
                    for(var renderer:unshadowed.renderers())scene.add(renderer);
                    for(int i=0;i<unshadowed.lights().size();i++) {
                        SceneLight l=unshadowed.lights().get(i);boolean shadow=i==1||i==2||i>=5;
                        scene.addLight(new SceneLight(l.type(),l.color(),l.intensity(),l.direction(),l.position(),
                                l.range(),l.innerConeRadians(),l.outerConeRadians(),shadow));
                    }
                    // A mathematical point has no visible emitting surface. Give the four
                    // ceiling spots an opaque fixture, just like the point-light markers.
                    Mesh fixture=Mesh.from(PbrSphereMesh.create(16,8));owned.add(fixture);
                    for(SceneLight light:scene.lights())if(light.type()==LightType.SPOT)
                        scene.add(SceneObject.fixed(fixture,black,new Matrix4f().translation(light.position()).scale(.12f),false));
                }
            }
            return new Bundle(id,scene,owned,id==SceneId.TAVERN_THRESHOLD?81:-1);
        } catch(RuntimeException|Error e) {
            try { new Bundle(id,new Scene(new Camera()),owned,-1).close(); }
            catch(RuntimeException close) { e.addSuppressed(close); }throw e;
        }
    }

    static void validateLights(Scene scene,VisualProfile profile) {
        for(var entry:profile.lightVolumes()) if(entry.sceneLightIndex()>=scene.lights().size())
            throw new IllegalArgumentException("profile light scene_index "+entry.sceneLightIndex()+" exceeds scene light count "+scene.lights().size());
    }

    static void applyLights(Scene scene,VisualProfile profile,boolean allLocal) {
        validateLights(scene,profile);
        for(int i=0;i<scene.lights().size();i++) scene.setLightVolumeHints(i,
                allLocal&&scene.lights().get(i).type()!=LightType.DIRECTIONAL?LightVolumeHints.DEFAULT:LightVolumeHints.DISABLED);
        if(!allLocal) for(var entry:profile.lightVolumes()) scene.setLightVolumeHints(entry.sceneLightIndex(),entry.hints());
    }
}
