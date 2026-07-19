# glTF scalability fixture 资产状态

- 本地路径：`src/demo/resources/gltf/scalability.gltf`
- 所有者：Haikalat project。
- 许可：project license，可随项目再分发。
- 生成事实：`tools/generate_gltf_scalability_fixture.py` 生成共享 position/UV 二进制块，节点、primitive 与材质保持为可审查 JSON。
- SHA-256：`a551e64c964662e7ea31cf837e2e11f1b9e1037a36bbf1461e6ff1e8bbdc2916`。
- 用途：v0.17 真实 glTF CPU submission、资源共享、static/queue cache 和材质碎片压力测试。
- 内容：2 个共享 mesh node、8 个 primitive、8 个 material identity、4 个纹理角色、OPAQUE/MASK、non-uniform 与 mirrored transform。

fixture 的 embedded PNG 与几何数据均由项目自行生成，不依赖第三方 showcase 资产。
