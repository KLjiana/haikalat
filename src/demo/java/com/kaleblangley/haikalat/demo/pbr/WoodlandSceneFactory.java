package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.material.*;
import com.kaleblangley.haikalat.core.BlendMode;
import com.kaleblangley.haikalat.core.CullMode;
import com.kaleblangley.haikalat.subsystems.render3d.*;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.*;
import org.joml.*;
import java.util.*;

/** Shared woodland geometry and light order; camera/capture behavior belongs to the runner. */
final class WoodlandSceneFactory {
    private WoodlandSceneFactory() { }
    static Scene create(WoodlandMeshes meshes, ShaderProgram shader,
                                     PbrFallbackTextures fallbacks, List<Material> owner,
                                     boolean grayModel) {
        Material neutral = grayModel ? own(owner, material(shader, fallbacks,
                new Vector4f(0.5f, 0.5f, 0.5f, 1.0f), 0.0f, 1.0f)) : null;
        Material ground = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(0.075f, 0.17f, 0.075f, 1.0f), 0.0f, 0.94f));
        Material bark = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(0.17f, 0.09f, 0.045f, 1.0f), 0.0f, 0.92f));
        Material leaf = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(0.10f, 0.27f, 0.065f, 1.0f), 0.0f, 0.86f));
        Material stone = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(0.20f, 0.27f, 0.29f, 1.0f), 0.0f, 0.82f));
        Material trail = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(.30f,.22f,.115f,1),0,.98f));
        Material paleLeaf = grayModel ? neutral : own(owner, material(shader, fallbacks,
                new Vector4f(.23f,.33f,.085f,1),0,.90f));
        Camera camera = new Camera(new Vector3f(6.5f, 3.1f, 13.5f));
        camera.setYaw(-106.0f);
        camera.setPitch(-5.0f);
        Scene scene = new Scene(camera);
        scene.add(SceneObject.fixed(meshes.terrain, ground, new Matrix4f(), true));
        scene.add(SceneObject.fixed(meshes.path, trail, new Matrix4f(), false));
        java.util.Random random = new java.util.Random(2400);
        for (int i = 0; i < 34; i++) {
            float z = 9.0f - (i / 2) * 3.9f + random.nextFloat() * 1.6f;
            float x = WoodlandMeshes.pathX(z) + (i % 2 == 0 ? -1 : 1) * (4.0f + random.nextFloat() * 8.0f);
            float scale = .80f + random.nextFloat() * .60f;
            Matrix4f transform = new Matrix4f().translation(x, WoodlandMeshes.height(x,z), z)
                    .rotateY(random.nextFloat() * 6.28f).scale(scale);
            scene.add(SceneObject.fixed(meshes.trunk, bark, transform, true));
            scene.add(SceneObject.fixed(meshes.crown, i % 4 == 0 ? paleLeaf : leaf, transform, true));
        }
        for (int i=0;i<24;i++) {
            float z=10-random.nextFloat()*54, x=WoodlandMeshes.pathX(z)+(i%2==0?-1:1)*(2+random.nextFloat()*5);
            float scale=.25f+random.nextFloat()*1.0f;
            scene.add(SceneObject.fixed(meshes.rock,stone,new Matrix4f()
                    .translation(x,WoodlandMeshes.height(x,z)+scale*.25f,z)
                    .rotateY(random.nextFloat()*6.28f).scale(scale),true));
        }
        for (int i=0;i<45;i++) {
            float z=12-random.nextFloat()*45, x=WoodlandMeshes.pathX(z)+(i%2==0?-1:1)*(1.6f+random.nextFloat()*3);
            scene.add(SceneObject.fixed(meshes.grass,i%3==0?paleLeaf:leaf,new Matrix4f()
                    .translation(x,WoodlandMeshes.height(x,z),z).rotateY(random.nextFloat()*6.28f)
                    .scale(.7f+random.nextFloat()*.9f),false));
        }
        StylizedSkySettings sky = StylizedSkySettings.morningFog();
        scene.addLight(SceneLight.shadowedDirectional(sky.sunDirection(), sky.sunColor(), sky.sunIntensity()));
        return scene;
    }

    private static Material material(ShaderProgram shader, PbrFallbackTextures fallbacks,
                                     Vector4f color, float metallic, float roughness) {
        return PbrMaterials.createWithBindings(shader,
                new com.kaleblangley.haikalat.core.assets.PbrMaterialProperties(
                        color, metallic, roughness, 1.0f, 1.0f, new Vector3f(), Map.of()),
                Map.of(), fallbacks, CullMode.BACK, false, false, 0.0f, BlendMode.OPAQUE);
    }

    private static Material own(List<Material> owner, Material material) {
        owner.add(material);
        return material;
    }

}
