"""生成 v0.17 自有 glTF scalability fixture 的确定性源数据。

正式资源保存在 src/demo/resources/gltf/scalability.gltf。该脚本记录 position/UV
二进制块的生成事实；材质和节点描述保持在可审查的 JSON 资源中。
"""

import base64
import struct


VALUES = (
    0.0, 0.0, 0.0,
    1.0, 0.0, 0.0,
    0.0, 1.0, 0.0,
    0.0, 0.0,
    1.0, 0.0,
    0.0, 1.0,
)


if __name__ == "__main__":
    payload = struct.pack("<15f", *VALUES)
    print(base64.b64encode(payload).decode("ascii"))
