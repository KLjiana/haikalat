# 真实能力闭环建设计划

> 实施状态：2026-07-11 已完成并补齐端到端画面验收。基础光照、固定分辨率方向光阴影、caster draw、shadow sampling、3x3 PCF、确定性基准场景、四种 AA GL smoke、Demo integration、Java 21 Toolchain、Windows/Linux CI 和架构依赖守卫均已落地。真实 GL 测试现已断言有光/无光、最终阴影像素、移动不可见 caster 和改变方向光均产生预期画面差异；多方向光通过显式 shadow light index 对齐 shadow pass 与 shader。第一版明确不包含 instanced shadow caster；该限制记录在能力矩阵和渲染边界文档中。

## 1. 背景

项目已经具备 OpenGL 资源封装、命令缓冲、RenderGraph、场景管线、材质、后处理、
资产配置、运行时统计和基础测试框架，但部分能力仍停留在“结构已经存在、最终行为尚未闭环”的阶段。

当前最典型的问题是方向光阴影：RenderGraph 中已经存在 shadow pass，运行时也会计算光空间矩阵，
但该 pass 尚未绘制 shadow caster，主场景 shader 也没有采样 shadow map。因此，本阶段不继续扩张功能面，
而是优先让文档中声明的每项核心能力都能由真实运行行为、自动化测试和稳定 Demo 共同证明。

本计划是后续一线开发的主执行路线。原有历史任务和设计讨论仍保留在其他文档中，但如有优先级冲突，
本计划优先。

## 2. 总体目标

完成本计划后，项目应满足以下条件：

- README 中列出的核心能力均能被运行中的基准场景证明。
- 方向光阴影是完整渲染链路，不再只是 pass 和矩阵计算骨架。
- 主 Demo 真实使用基础光照、材质和阴影数据。
- none、MSAA、FXAA、TAA 路径至少具备可重复的启动和输出验证。
- 单元测试、GL smoke、Demo integration 三层测试职责清晰。
- JDK、操作系统和 OpenGL 运行要求由构建与 CI 明确约束。
- 在行为闭环前，不继续引入 PBR、第二图形后端或大型编辑器能力。

## 3. 执行原则

1. 先证明行为，再增加抽象。
2. 每个长期保留的能力必须至少有一个稳定 Demo 和一个自动化验证入口。
3. 文档只能描述当前已经实现的事实；未闭环能力标记为实验或骨架。
4. 优先修复主渲染路径，不为未来 Vulkan 预先扩大接口。
5. 普通 CI 必须继续支持无桌面 OpenGL 环境；真实 GL 检查保持独立入口。
6. 每个阶段单独提交，前一阶段验收通过后再进入下一阶段。

## 4. 阶段一：建立可信基线（P0）

### 目标

明确“已经完成、部分完成、仅有骨架”的能力边界，防止后续继续在错误基线上扩展。

### 任务

- 建立核心能力矩阵，至少覆盖：
  - RenderGraph pass 排序和资源生命周期；
  - 基础材质和纹理；
  - 方向光、点光、聚光参数；
  - 方向光阴影；
  - instancing；
  - none、MSAA、FXAA、TAA；
  - 模型加载和场景配置；
  - 异步上传和 GL render thread；
  - CPU/GPU profiling。
- 将每项能力标记为 `完整`、`部分完成` 或 `骨架`，并附对应源码、Demo 和测试入口。
- 修正 README、`docs/planning/project-goals.md` 和 `docs/planning/future-plans.md` 中超前于实现的表述。
- 固定当前构建基线，并记录默认测试与 GL smoke 的测试数量和执行命令。
- 在 Gradle 中配置 Java 21 Toolchain 或等价的明确版本约束。
- 为 CI 增加 Linux 编译与纯 JVM 测试任务；GL smoke 暂不要求在公共 CI 中运行。

### 验收标准

- 新工程师只阅读 README 和能力矩阵即可分辨正式能力与实验能力。
- `compileJava demoClasses test` 在 Windows 和 Linux CI 中通过。
- 本地 GL smoke 全部通过。
- 构建使用非 JDK 21 时给出明确错误或自动选择配置的 Toolchain。
- 文档中不再把未写入深度纹理的 shadow pass 描述成完整阴影实现。

## 5. 阶段二：完成基础光照链路（P0）

### 目标

让主 Demo 的灯光数据真正影响最终像素，为阴影实现提供可靠的几何和材质基础。

### 任务

- 为参与光照的网格明确法线顶点属性和 layout 约定。
- 为主 Demo 增加一套稳定的基础光照 shader，建议采用最小 Blinn-Phong 模型。
- shader 至少消费：
  - 模型、视图、投影矩阵；
  - 法线矩阵；
  - 相机位置；
  - 一个方向光；
  - 可选点光；
  - 材质基础色或纹理。
- 明确无光照材质与受光材质的使用边界，不强迫所有 shader 声明无用 lighting uniform。
- 为 `LightingBinder` 增加数量上限和超限行为，避免灯光数量无限写入 shader 数组。
- 为非法或退化光照输入增加验证，例如零方向向量、负范围和非法 cone 参数。
- 将基准场景调整为能够清楚观察法线、明暗变化和相机移动的简单几何组合。

### 验收标准

- 修改方向光方向、颜色或强度会稳定改变主 Demo 输出。
- 相机移动时高光和视角相关光照行为正确。
- 至少一个纯 JVM 测试验证灯光选择、计数上限和参数校验。
- 至少一个 GL integration 检查验证“有光”和“无光”产生不同输出。
- 主 Demo 不依赖 `trySetUniform*` 静默忽略所有灯光参数来维持运行。

## 6. 阶段三：完成方向光阴影闭环（P0）

### 目标

实现真正可见、可验证、可调整的单方向光 shadow map。

### 任务

#### 6.1 Shadow target

- 使用 `ShadowSettings.resolution` 创建固定分辨率深度纹理，不能跟随窗口尺寸。
- 明确 shadow framebuffer 的创建、resize 和 close 归属。
- 窗口 resize 不应无意义地重建固定分辨率 shadow map。
- 为 shadow texture 设置适合深度采样的过滤和 wrap 策略。

#### 6.2 Shadow pass

- 增加 depth-only vertex/fragment shader。
- 遍历 `castShadows=true` 的场景对象并记录真实绘制命令。
- 支持普通 mesh；instanced caster 是否进入第一版由实现复杂度决定，但必须在文档中明确。
- Shadow pass 设置正确的 viewport、深度状态和必要的 cull 策略。
- 删除或避免只计算矩阵、不写入深度纹理的空 pass。

#### 6.3 Geometry pass

- 将 shadow depth texture 作为逻辑资源传递给 geometry pass。
- 在受光材质中绑定 shadow texture 和 light-space matrix。
- 实现基础阴影比较，并增加可配置 bias。
- 第一版至少实现 3x3 PCF；如暂不实现，必须记录清晰的锯齿限制。
- 正确处理超出 light frustum 的采样坐标。

#### 6.4 稳定性

- 处理方向光接近平行于世界 up 向量时的 `lookAt` 退化问题。
- 明确阴影相机 near/far、scene radius 和 focus 的配置来源。
- 防止 shadow acne、peter-panning 和错误自阴影达到不可接受程度。

### 验收标准

- Shadow pass 中存在可观测的 caster draw call。
- 深度纹理不再是全默认值或仅有 clear 值。
- 移动 caster、receiver 或方向光时，阴影位置正确更新。
- `castShadows=false` 的对象不会写入 shadow map。
- 没有投影到 shadow frustum 内的对象不会产生伪影。
- 固定基准场景中至少选取若干像素验证受光区和阴影区存在稳定差异。
- GL debug callback 不报告 framebuffer、texture 或状态相关错误。

## 7. 阶段四：建立稳定基准场景（P1）

### 目标

用一个长期维护的场景证明主渲染路径，停止为每项功能增加新的永久 Demo。

### 基准场景内容

- 一个地面或墙面 receiver。
- 至少两个不同 transform 的 caster。
- 一个不投射阴影的对象。
- 一个纹理材质和一个纯色材质。
- 一个方向光，可选一个点光。
- 一组实例化对象。
- 能观察遮挡、深度、透明和抗锯齿边缘的固定相机初始位置。

### 任务

- 将 `LearnOpenGlDemo` 收敛为唯一综合基准场景。
- 保留 `MinimalDemo` 作为最小窗口/命令路径检查。
- 保留 `AsyncDemo` 作为线程和上传专项检查。
- 为基准场景增加确定性模式：
  - 固定窗口尺寸；
  - 固定相机；
  - 固定帧编号或关闭动画；
  - 固定随机种子；
  - 可在若干帧后自动退出。
- 调试标题或 overlay 中显示当前 AA、draw call、instance 数量和 GPU timing 状态。

### 验收标准

- 综合能力只需要运行 `LearnOpenGlDemo` 即可观察。
- 自动化模式连续运行多次输出一致或处于明确容差内。
- 不新增与现有三个 Demo 职责重复的入口。
- Demo 资源、材质、对象和灯光配置均能追溯到统一 manifest。

## 8. 阶段五：补齐端到端验证（P1）

### 目标

让测试不仅证明描述符和 pass 名称存在，还能证明最终渲染结果有效。

### 测试分层

#### Unit

- RenderGraph 拓扑、资源声明和非法依赖。
- 光照参数校验和灯光数量上限。
- 光空间矩阵的有限值、方向变化和退化输入。
- Shadow caster 筛选。
- AA pass 计划和 resize 规则。
- 线程状态机和上传请求合并。

#### GL smoke

- Shader 编译和链接。
- Shadow framebuffer 完整性和固定分辨率。
- 深度纹理写入和采样。
- framebuffer、texture、shader、sampler 的 use-after-close。
- none、MSAA、FXAA、TAA 的最小初始化和一帧执行。

#### Integration

- 基准场景启动、渲染若干帧并正常退出。
- resize、最小化恢复和资源重建。
- 光照开关、阴影开关产生预期像素差异。
- 各 AA 模式输出非空，且没有 GL error。
- 如采用截图回归，允许配置合理的跨 GPU 容差，避免要求逐像素完全一致。

### 验收标准

- CI 默认单元测试仍不需要桌面 GL context。
- 本地一条命令可执行全部 GL smoke。
- 一条独立命令可执行基准场景 integration。
- Pipeline 测试不再只断言 pass 名称；至少有一条路径验证最终像素或 framebuffer 内容。
- 测试失败能够指出是资源生命周期、pass 编排、shader 还是最终画面问题。

## 9. 阶段六：收窄架构边界（P2）

### 目标

在真实行为稳定后处理依赖方向，避免为了修复尚未闭环的功能而过早重构。

### 任务

- 画出并记录当前真实依赖关系，而不是只记录期望分层。
- 决定 `core` 的定位：
  - 若项目保持 OpenGL 专用，允许部分 GL runtime 类型存在，但要明确命名；
  - 若准备验证第二后端，则将纯数据协议与 GL 实现彻底分离。
- 优先消除 `backend -> core -> backend` 双向依赖。
- 评估将 GPU timer/profile 作为 RenderGraph observer 或可选 instrumentation，而非拓扑核心职责。
- 继续降低 `PassResources` 中 backend-facing API 的默认可见度。
- 增加架构依赖测试或 Gradle 模块边界，防止分层重新恶化。
- 不在本阶段实现 Vulkan；第二后端只能作为后续验证任务。

### 验收标准

- 每个顶层包或模块都有明确的允许依赖方向。
- 构建或测试能够阻止新的非法跨层依赖。
- 普通 pass 优先通过逻辑资源名工作。
- RenderGraph 的拓扑职责、资源职责和 profiling 职责可分别测试。
- 行为与阶段五的基准输出保持一致。

## 10. 暂不执行事项

在阶段一至阶段五全部验收前，不启动以下工作：

- Vulkan、Metal 或其他第二图形后端。
- 复杂 PBR、IBL、SSR、SSAO、体积光和级联阴影。
- ECS、脚本系统、物理、音频和动画状态机。
- 大型场景编辑器或资源编辑器。
- 为尚未出现的多实现需求新增 factory、manager、descriptor 或 public interface。
- 为展示单个新特性创建新的长期 Demo 入口。

## 11. 推荐提交顺序

建议以小提交或小型 PR 推进，避免一个分支同时改动 shader、RenderGraph、材质和测试体系。

1. 文档能力矩阵与构建基线。
2. Java 21 Toolchain 与 Windows/Linux CI。
3. 基础受光 shader、法线数据和灯光边界。
4. 固定分辨率 shadow target。
5. Depth-only shadow caster 绘制。
6. Geometry pass shadow sampling、bias 和 PCF。
7. 基准场景确定性模式。
8. Shadow/lighting GL smoke。
9. Demo integration 和像素/截图验证。
10. RenderGraph 与包依赖边界收窄。

每个提交必须保持默认单元测试通过；涉及真实 GL 的提交同时附带本地 GL smoke 结果。

## 12. 完成定义

本路线只有在以下条件全部满足时才算完成：

- README 的方向光、阴影、后处理和 Demo 描述与实际行为一致。
- 主 Demo 中光照和阴影肉眼可见，并可被自动化结果验证。
- 64 个现有测试保持通过，新增测试覆盖本路线的关键行为。
- Windows/Linux 默认 CI 通过，本地 GL smoke 和 integration 通过。
- 基准场景可重复运行，无 GL error、资源泄漏或 resize 生命周期错误。
- 未通过验收的能力不会以“已支持”形式出现在项目说明中。
