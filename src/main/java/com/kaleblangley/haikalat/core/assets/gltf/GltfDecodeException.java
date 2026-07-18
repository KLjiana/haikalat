package com.kaleblangley.haikalat.core.assets.gltf;

/** CPU 解码阶段携带精确 glTF location 的内部异常。 */
final class GltfDecodeException extends RuntimeException {
    private final String location;

    GltfDecodeException(String location, String message) {
        super(message);
        this.location = location;
    }

    String location() {
        return location;
    }
}
