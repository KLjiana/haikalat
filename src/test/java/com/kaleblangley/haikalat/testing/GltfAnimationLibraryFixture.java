package com.kaleblangley.haikalat.testing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Deterministic model-plus-external-animation fixture for loader and scene tests. */
public final class GltfAnimationLibraryFixture {
    private GltfAnimationLibraryFixture() {
    }

    public static Path write(Path root) throws IOException {
        return write(root, "wave", "arm");
    }

    public static Path write(Path root, String animationName,
                             String bindingName) throws IOException {
        Files.createDirectories(root.resolve("model"));
        Files.createDirectories(root.resolve("animations"));
        String encoded = animationPayload();
        Files.writeString(root.resolve("model/actor.gltf"), """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0]}],
                  "nodes":[{"name":"root","children":[1]},{"name":"arm"}],
                  "buffers":[{"byteLength":40,"uri":"data:application/octet-stream;base64,%s"}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":8},
                    {"buffer":0,"byteOffset":8,"byteLength":32}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":2,"type":"SCALAR"},
                    {"bufferView":1,"componentType":5126,"count":2,"type":"VEC4"}
                  ],
                  "animations":[{
                    "name":"idle",
                    "samplers":[{"input":0,"output":1,"interpolation":"LINEAR"}],
                    "channels":[{"sampler":0,"target":{"node":1,"path":"rotation"}}]
                  }]
                }
                """.formatted(encoded), StandardCharsets.UTF_8);
        Files.writeString(root.resolve(
                "animations/wave.animation.gltf.json"), """
                {
                  "asset":{"version":"2.0"},
                  "nodes":[{"name":"arm"}],
                  "buffers":[{"byteLength":40,"uri":"data:application/octet-stream;base64,%s"}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":8},
                    {"buffer":0,"byteOffset":8,"byteLength":32}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":2,"type":"SCALAR"},
                    {"bufferView":1,"componentType":5126,"count":2,"type":"VEC4"}
                  ],
                  "animations":[{
                    "name":"exported_name",
                    "samplers":[{"input":0,"output":1,"interpolation":"LINEAR"}],
                    "channels":[{"sampler":0,"target":{"node":0,"path":"rotation"}}],
                    "extras":{"markers":[{"name":"hit_start","timeSeconds":0.5}]}
                  }]
                }
                """.formatted(encoded), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("animation-library.json"), """
                {
                  "schema":"haikalat.gltf-animation-library/1",
                  "generatedBy":"test",
                  "pluginVersion":"1.4.0",
                  "gltfVersion":"2.0",
                  "model":"model/actor.gltf",
                  "nodeBinding":"name",
                  "animations":[{
                    "name":"%s",
                    "file":"animations/wave.animation.gltf.json",
                    "selfContained":true,
                    "nodeBindings":[{
                      "sidecarNode":0,
                      "sourceNode":1,
                      "name":"%s"
                    }]
                  }]
                }
                """.formatted(animationName, bindingName), StandardCharsets.UTF_8);
        return root;
    }

    public static void zip(Path source, Path archive,
                           String prefix) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            put(output, prefix + "animation-library.json",
                    Files.readString(source.resolve("animation-library.json")));
            put(output, prefix + "model/actor.gltf",
                    Files.readString(source.resolve("model/actor.gltf")));
            put(output, prefix + "animations/wave.animation.gltf.json",
                    Files.readString(source.resolve(
                            "animations/wave.animation.gltf.json")));
        }
    }

    private static void put(ZipOutputStream output, String name,
                            String contents) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String animationPayload() {
        ByteBuffer bytes = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putFloat(0.0f).putFloat(1.0f);
        bytes.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        float halfSqrt = (float) Math.sqrt(0.5);
        bytes.putFloat(0.0f).putFloat(0.0f).putFloat(halfSqrt).putFloat(halfSqrt);
        return Base64.getEncoder().encodeToString(bytes.array());
    }
}
