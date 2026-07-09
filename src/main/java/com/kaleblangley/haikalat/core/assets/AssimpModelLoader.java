package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.assimp.Assimp.aiImportFile;
import static org.lwjgl.assimp.Assimp.aiProcess_GenNormals;
import static org.lwjgl.assimp.Assimp.aiProcess_JoinIdenticalVertices;
import static org.lwjgl.assimp.Assimp.aiProcess_Triangulate;
import static org.lwjgl.assimp.Assimp.aiReleaseImport;

public final class AssimpModelLoader implements ModelAssetLoader {
    private static final int IMPORT_FLAGS = aiProcess_Triangulate
            | aiProcess_JoinIdenticalVertices
            | aiProcess_GenNormals;

    private final ResourceLocator locator;

    public AssimpModelLoader(ResourceLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    @Override
    public LoadedModel load(AssetRef ref) {
        Path file = locator.resolveFile(ref)
                .orElseThrow(() -> new GlException("Assimp assets must resolve to a filesystem path: " + ref.path()));
        AIScene scene = aiImportFile(file.toString(), IMPORT_FLAGS);
        if (scene == null) {
            throw new GlException("Assimp failed to load model: " + ref.path());
        }
        try {
            return convert(scene, ref.path());
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static LoadedModel convert(AIScene scene, String assetName) {
        PointerBuffer meshes = scene.mMeshes();
        if (meshes == null || scene.mNumMeshes() == 0) {
            throw new GlException("Model contains no meshes: " + assetName);
        }
        List<MeshData> result = new ArrayList<>(scene.mNumMeshes());
        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(meshes.get(i));
            result.add(convertMesh(mesh, assetName + "#" + i));
        }
        return new LoadedModel(result);
    }

    private static MeshData convertMesh(AIMesh mesh, String name) {
        AIVector3D.Buffer positions = mesh.mVertices();
        AIVector3D.Buffer normals = mesh.mNormals();
        AIVector3D.Buffer texCoords = mesh.mTextureCoords(0);
        int vertexCount = mesh.mNumVertices();
        float[] vertices = new float[vertexCount * 8];

        for (int i = 0; i < vertexCount; i++) {
            AIVector3D position = positions.get(i);
            AIVector3D normal = normals != null ? normals.get(i) : null;
            AIVector3D uv = texCoords != null ? texCoords.get(i) : null;
            int base = i * 8;
            vertices[base] = position.x();
            vertices[base + 1] = position.y();
            vertices[base + 2] = position.z();
            vertices[base + 3] = normal == null ? 0.0f : normal.x();
            vertices[base + 4] = normal == null ? 0.0f : normal.y();
            vertices[base + 5] = normal == null ? 1.0f : normal.z();
            vertices[base + 6] = uv == null ? 0.0f : uv.x();
            vertices[base + 7] = uv == null ? 0.0f : uv.y();
        }

        List<Integer> indices = new ArrayList<>();
        AIFace.Buffer faces = mesh.mFaces();
        for (int i = 0; i < mesh.mNumFaces(); i++) {
            IntBuffer faceIndices = faces.get(i).mIndices();
            while (faceIndices.hasRemaining()) {
                indices.add(faceIndices.get());
            }
        }
        return LoadedModel.VertexFormat.POSITION_NORMAL_UV.meshData(
                name, vertices, indices.stream().mapToInt(Integer::intValue).toArray());
    }
}
