package com.kaleblangley.haikalat.testing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/** Deterministic four-target glTF fixture with default/node weights and all interpolations. */
public final class MorphGltfFixture {
    private static final int TARGET_COUNT = 4;

    private MorphGltfFixture() {
    }

    public static String document() {
        String encoded = Base64.getEncoder().encodeToString(payload());
        StringBuilder views = new StringBuilder();
        StringBuilder accessors = new StringBuilder();
        appendView(views, 0, 36);
        appendAccessor(accessors, 0, 3, "VEC3");
        for (int target = 0; target < TARGET_COUNT * 3; target++) {
            appendView(views, 36 + target * 36, 36);
            appendAccessor(accessors, target + 1, 3, "VEC3");
        }
        appendView(views, 468, 8);
        appendAccessor(accessors, 13, 2, "SCALAR");
        appendView(views, 476, 32);
        appendAccessor(accessors, 14, 8, "SCALAR");
        appendView(views, 508, 32);
        appendAccessor(accessors, 15, 8, "SCALAR");
        appendView(views, 540, 96);
        appendAccessor(accessors, 16, 24, "SCALAR");
        appendView(views, 636, 12);
        accessors.append("{\"bufferView\":17,\"componentType\":5121,"
                + "\"count\":3,\"type\":\"VEC4\"},");
        appendView(views, 648, 48);
        appendAccessor(accessors, 18, 3, "VEC4");
        appendView(views, 696, 128);
        appendAccessor(accessors, 19, 2, "MAT4");
        appendView(views, 824, 72);
        appendAccessor(accessors, 20, 6, "VEC3");
        trimComma(views);
        trimComma(accessors);
        return """
                {"asset":{"version":"2.0"},"scene":0,
                 "scenes":[{"nodes":[0,1,2]}],
                 "nodes":[
                   {"name":"default-face","mesh":0},
                   {"name":"override-face","mesh":0,"translation":[2,0,0],
                    "weights":[0.4,0.3,0.2,0.1]},
                   {"name":"skinned-face","mesh":1,"skin":0,"children":[3],
                    "translation":[-1.25,0,0]},
                   {"name":"root-joint","children":[4]},
                   {"name":"tip-joint","translation":[0,1,0]}],
                 "buffers":[{"byteLength":896,
                   "uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[%s],
                 "accessors":[%s],
                 "meshes":[{"name":"face","weights":[0.1,0.2,0.3,0.4],
                   "primitives":[{"attributes":{"POSITION":0},"targets":[
                     {"POSITION":1,"NORMAL":2,"TANGENT":3},
                     {"POSITION":4,"NORMAL":5,"TANGENT":6},
                     {"POSITION":7,"NORMAL":8,"TANGENT":9},
                     {"POSITION":10,"NORMAL":11,"TANGENT":12}]}]},
                   {"name":"skinned-face","weights":[0.1,0.2,0.3,0.4],
                    "primitives":[{"attributes":{"POSITION":0,"JOINTS_0":17,
                     "WEIGHTS_0":18},"targets":[
                     {"POSITION":1,"NORMAL":2,"TANGENT":3},
                     {"POSITION":4,"NORMAL":5,"TANGENT":6},
                     {"POSITION":7,"NORMAL":8,"TANGENT":9},
                     {"POSITION":10,"NORMAL":11,"TANGENT":12}]}]}],
                 "skins":[{"name":"face-skin","skeleton":3,"joints":[3,4],
                   "inverseBindMatrices":19}],
                 "animations":[
                   {"name":"linear-face","samplers":[{"input":13,"output":14},
                     {"input":13,"output":20,"interpolation":"CUBICSPLINE"}],
                    "channels":[
                     {"sampler":0,"target":{"node":0,"path":"weights"}},
                     {"sampler":1,"target":{"node":4,"path":"translation"}}]},
                   {"name":"step-face","samplers":[{"input":13,"output":15,
                     "interpolation":"STEP"}],
                    "channels":[{"sampler":0,"target":{"node":0,"path":"weights"}}]},
                   {"name":"cubic-face","samplers":[{"input":13,"output":16,
                     "interpolation":"CUBICSPLINE"}],
                    "channels":[{"sampler":0,"target":{"node":0,"path":"weights"}}]}]}
                """.formatted(encoded, views, accessors);
    }

    private static byte[] payload() {
        ByteBuffer output = ByteBuffer.allocate(896).order(ByteOrder.LITTLE_ENDIAN);
        putTriangle(output);
        for (int target = 0; target < TARGET_COUNT; target++) {
            putTargetPositions(output, target);
            putTargetNormals(output, target);
            putTargetTangents(output, target);
        }
        output.putFloat(0.0f).putFloat(1.0f);
        putWeights(output, 0, 0, 0, 0);
        putWeights(output, 1, 0.5f, 0.25f, 0.125f);
        putWeights(output, 0, 0, 0, 0);
        putWeights(output, 1, 1, 1, 1);
        // CUBICSPLINE: in, value, out for each keyframe.
        putWeights(output, 0, 0, 0, 0);
        putWeights(output, 0, 0, 0, 0);
        putWeights(output, 1, 0, 0, 0);
        putWeights(output, 1, 0, 0, 0);
        putWeights(output, 0.5f, 0.25f, 0.125f, 0.0f);
        putWeights(output, 0, 0, 0, 0);
        output.put(new byte[]{
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 0, 0});
        putWeights(output, 1, 0, 0, 0);
        putWeights(output, 1, 0, 0, 0);
        putWeights(output, 0.5f, 0.5f, 0, 0);
        putTranslationMatrix(output, 0, 0, 0);
        putTranslationMatrix(output, 0, -1, 0);
        putVec3(output, 0, 0, 0);
        putVec3(output, 0, 1, 0);
        putVec3(output, 0, 1, 0);
        putVec3(output, 0, 1, 0);
        putVec3(output, 0, 2, 0);
        putVec3(output, 0, 0, 0);
        return output.array();
    }

    private static void putTriangle(ByteBuffer output) {
        putVec3(output, -0.5f, -0.5f, 0);
        putVec3(output, 0.5f, -0.5f, 0);
        putVec3(output, 0, 0.5f, 0);
    }

    private static void putTargetPositions(ByteBuffer output, int target) {
        float amount = 0.1f * (target + 1);
        putVec3(output, target == 0 ? -amount : 0, 0, amount);
        putVec3(output, target == 1 ? amount : 0, 0, amount);
        putVec3(output, 0, target == 2 ? amount : 0, amount);
    }

    private static void putTargetNormals(ByteBuffer output, int target) {
        float amount = 0.01f * (target + 1);
        for (int vertex = 0; vertex < 3; vertex++) putVec3(output, amount, 0, 0);
    }

    private static void putTargetTangents(ByteBuffer output, int target) {
        float amount = 0.01f * (target + 1);
        for (int vertex = 0; vertex < 3; vertex++) putVec3(output, 0, amount, 0);
    }

    private static void putWeights(ByteBuffer output, float a, float b, float c, float d) {
        output.putFloat(a).putFloat(b).putFloat(c).putFloat(d);
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

    private static void appendView(StringBuilder output, int offset, int length) {
        output.append("{\"buffer\":0,\"byteOffset\":").append(offset)
                .append(",\"byteLength\":").append(length).append("},");
    }

    private static void appendAccessor(StringBuilder output, int view, int count, String type) {
        output.append("{\"bufferView\":").append(view)
                .append(",\"componentType\":5126,\"count\":").append(count)
                .append(",\"type\":\"").append(type).append("\"},");
    }

    private static void trimComma(StringBuilder value) {
        value.setLength(value.length() - 1);
    }
}
