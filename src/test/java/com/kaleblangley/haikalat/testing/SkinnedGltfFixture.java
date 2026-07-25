package com.kaleblangley.haikalat.testing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/** Deterministic two-joint glTF fixture shared by CPU and real-GL tests. */
public final class SkinnedGltfFixture {
    private SkinnedGltfFixture() {
    }

    public static String document() {
        return document(1, 6);
    }

    public static String document(int maximumJointIndex, int cubicOutputCount) {
        String encoded = Base64.getEncoder().encodeToString(payload(maximumJointIndex));
        return """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"nodes":[0]}],
                 "nodes":[
                   {"name":"mesh","mesh":0,"skin":0,"children":[1]},
                   {"name":"rootJoint","children":[2]},
                   {"name":"tipJoint","translation":[0,1,0]}],
                 "buffers":[{"byteLength":304,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[
                   {"buffer":0,"byteOffset":0,"byteLength":36},
                   {"buffer":0,"byteOffset":36,"byteLength":12},
                   {"buffer":0,"byteOffset":48,"byteLength":48},
                   {"buffer":0,"byteOffset":96,"byteLength":128},
                   {"buffer":0,"byteOffset":224,"byteLength":8},
                   {"buffer":0,"byteOffset":232,"byteLength":72}],
                 "accessors":[
                   {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                   {"bufferView":1,"componentType":5121,"count":3,"type":"VEC4"},
                   {"bufferView":2,"componentType":5126,"count":3,"type":"VEC4"},
                   {"bufferView":3,"componentType":5126,"count":2,"type":"MAT4"},
                   {"bufferView":4,"componentType":5126,"count":2,"type":"SCALAR"},
                   {"bufferView":5,"componentType":5126,"count":%d,"type":"VEC3"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,
                   "JOINTS_0":1,"WEIGHTS_0":2}}]}],
                 "skins":[{"name":"two-joint","skeleton":1,"joints":[1,2],
                   "inverseBindMatrices":3}],
                 "animations":[{"name":"lift","samplers":[{"input":4,"output":5,
                   "interpolation":"CUBICSPLINE"}],"channels":[{"sampler":0,
                   "target":{"node":2,"path":"translation"}}]}]}
                """.formatted(encoded, cubicOutputCount);
    }

    private static byte[] payload(int maximumJointIndex) {
        ByteBuffer payload = ByteBuffer.allocate(304).order(ByteOrder.LITTLE_ENDIAN);
        payload.putFloat(0).putFloat(0).putFloat(0);
        payload.putFloat(1).putFloat(0).putFloat(0);
        payload.putFloat(0).putFloat(1).putFloat(0);
        payload.put(new byte[]{0, 0, 0, 0, 1, 0, 0, 0,
                0, (byte) maximumJointIndex, 0, 0});
        payload.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        payload.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        payload.putFloat(0.25f).putFloat(0.75f).putFloat(0).putFloat(0);
        putTranslationMatrix(payload, 0.0f, 0.0f, 0.0f);
        putTranslationMatrix(payload, 0.0f, -1.0f, 0.0f);
        payload.putFloat(0.0f).putFloat(1.0f);
        putVec3(payload, 0, 0, 0);
        putVec3(payload, 0, 1, 0);
        putVec3(payload, 0, 1, 0);
        putVec3(payload, 0, 1, 0);
        putVec3(payload, 0, 2, 0);
        putVec3(payload, 0, 0, 0);
        return payload.array();
    }

    private static void putVec3(ByteBuffer output, float x, float y, float z) {
        output.putFloat(x).putFloat(y).putFloat(z);
    }

    private static void putTranslationMatrix(ByteBuffer output, float x, float y, float z) {
        output.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        output.putFloat(0).putFloat(1).putFloat(0).putFloat(0);
        output.putFloat(0).putFloat(0).putFloat(1).putFloat(0);
        output.putFloat(x).putFloat(y).putFloat(z).putFloat(1);
    }
}
