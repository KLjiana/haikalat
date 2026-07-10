package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetPipelineTest {
    @Test
    void assetRefNormalizesPathsAndExtractsExtension() {
        AssetRef ref = AssetRef.of("models\\cube.OBJ");

        assertEquals("models/cube.OBJ", ref.path());
        assertEquals("obj", ref.extension());
    }

    @Test
    void resourceLocatorReadsFilesystemRoots() throws Exception {
        Path root = Files.createTempDirectory("haikalat-assets");
        Files.writeString(root.resolve("scene.txt"), "shader basic a.vert a.frag");
        ResourceLocator locator = ResourceLocator.classpath(getClass()).addRoot(root);

        assertEquals("shader basic a.vert a.frag", locator.readString(AssetRef.of("scene.txt")));
        assertTrue(locator.resolveFile(AssetRef.of("scene.txt")).isPresent());
    }

    @Test
    void sceneConfigParsesShadersTexturesObjectsAndLights() {
        SceneAssetConfig config = SceneAssetConfig.parse("""
                shader color /demo/color.vert /demo/color.frag
                texture wall /wall.png false
                material wallMat color alpha false
                model cube /models/cube.obj
                object cube01 cube wallMat 1 2 3 0 0 0 1 true
                light sun directional -1 -1 -1 1 1 1 2 0 true
                """);

        assertEquals("/demo/color.vert", config.shaders().get("color").vertexShader().path());
        assertEquals(false, config.textures().get("wall").flipVertically());
        assertEquals("color", config.materials().get("wallMat").shader());
        assertEquals(false, config.materials().get("wallMat").depthTest());
        assertEquals("cube", config.objects().get("cube01").model());
        assertEquals("directional", config.lights().get("sun").type());
    }

    @Test
    void sceneConfigParsesStandardPropertiesFormat() {
        SceneAssetConfig config = SceneAssetConfig.parseProperties("""
                shader.color.vertex=/demo/color.vert
                shader.color.fragment=/demo/color.frag
                shader.textured.vertex=/demo/textured.vert
                shader.textured.fragment=/demo/textured.frag
                texture.wall.path=/wall.png
                texture.wall.flipVertically=false
                material.wall.shader=textured
                material.wall.blend=alpha
                material.wall.depthTest=false
                material.wall.texture.uTexture=wall
                material.wall.texture.uTexture.unit=2
                model.cube.path=/models/cube.obj
                object.cube01.model=cube
                object.cube01.material=wall
                object.cube01.position=1,2,3
                object.cube01.rotation=0,0,0
                object.cube01.scale=1
                object.cube01.castShadows=true
                light.sun.type=directional
                light.sun.vector=-1,-1,-1
                light.sun.color=1,1,1
                light.sun.intensity=2
                light.sun.range=0
                light.sun.castShadows=true
                """);

        assertEquals("/demo/color.vert", config.shaders().get("color").vertexShader().path());
        assertEquals(false, config.textures().get("wall").flipVertically());
        MaterialDef wall = config.materials().get("wall");
        assertEquals("textured", wall.shader());
        assertEquals(com.kaleblangley.haikalat.core.BlendMode.ALPHA, wall.blendMode());
        assertEquals(false, wall.depthTest());
        assertEquals("uTexture", wall.textures().get(0).samplerName());
        assertEquals("wall", wall.textures().get(0).texture());
        assertEquals(2, wall.textures().get(0).unit());
        assertEquals("/models/cube.obj", config.models().get("cube").path().path());
        assertEquals(1.0f, config.objects().get("cube01").position().x, 1.0e-6f);
        assertEquals(true, config.lights().get("sun").castShadows());
    }

    @Test
    void sceneConfigRejectsUnknownBuiltinMeshNames() {
        assertThrows(GlException.class, () -> SceneAssetConfig.parseProperties("""
                object.bad.model=builtin:customCylinder
                object.bad.material=mat
                object.bad.position=0,0,0
                object.bad.rotation=0,0,0
                """));
    }

    @Test
    void objLoaderTriangulatesQuadsAndBuildsPositionNormalUvVertices() {
        LoadedModel model = ObjModelLoader.parse("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                vn 0 0 1
                f 1/1/1 2/2/1 3/3/1 4/4/1
                """, "quad.obj");

        assertEquals(1, model.meshes().size());
        assertEquals(8 * Float.BYTES, model.firstMesh().layout().strideBytes());
        assertEquals(3, model.firstMesh().layout().attributes().size());
        assertEquals(4 * 8, model.firstMesh().vertices().length);
        assertEquals(6, model.firstMesh().indices().length);
    }

    @Test
    void modelManagerRoutesByExtension() {
        ModelAssetManager manager = new ModelAssetManager()
                .register("obj", ref -> new LoadedModel(java.util.List.of(
                        LoadedModel.VertexFormat.POSITION.meshData(ref.path(), new float[]{0, 0, 0}, new int[]{0}))));

        assertTrue(manager.supports(".obj"));
        assertEquals("mesh.obj", manager.load("mesh.obj").firstMesh().name());
    }

    @Test
    void builtinMeshDataIsPureDataAndValidatesLayout() {
        MeshData quad = BuiltinMeshData.texturedQuad("quad");

        assertEquals("quad", quad.name());
        assertEquals(4, quad.vertexCount());
        assertEquals(6, quad.indices().length);
        assertEquals(8 * Float.BYTES, quad.layout().strideBytes());
        assertEquals(3, quad.layout().attributes().size());
    }

    @Test
    void textureCacheReusesLoadedTexture() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        Texture2D texture = texture(42);
        TextureAssetCache cache = new TextureAssetCache(ref -> {
            loads.incrementAndGet();
            return texture;
        });

        assertSame(texture, cache.get("wall.png"));
        assertSame(texture, cache.get("wall.png"));
        assertEquals(1, loads.get());
        assertEquals(1, cache.size());
    }

    private static Texture2D texture(int id) throws Exception {
        Constructor<Texture2D> ctor = Texture2D.class.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, 1, 1, 0);
    }
}
