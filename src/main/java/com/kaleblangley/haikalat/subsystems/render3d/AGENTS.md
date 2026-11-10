# Render3D 指引

本文件补充[根指引](../../../../../../../../AGENTS.md)。作用于本目录；根指引同时要求关联的 postprocess、渲染 shader 和共享表面协议变更阅读本文件。

按需查阅[模块合同](../../../../../../../../docs/architecture/v0.27.3-render3d-module-contracts.md)、[包迁移清单](../../../../../../../../docs/architecture/v0.27.3-render3d-package-migration.md)、[共享表面合同](../../../../../../../../docs/architecture/v0.27.2-shared-scene-surface-contract.md)及[统一控制合同](../../../../../../../../docs/architecture/v0.27.4-render3d-feature-control.md)。旧计划中的类名和源码行号不作为当前归属依据。

## 模块边界

- `RenderPipeline` 保留公共配置与帧协调，`pipeline` 负责候选装配、代次发布、借用/转交和退役；算法、shader 参数、Pass 与专属 GPU owner 留在所属功能模块。
- 根包保留稳定入口、配置与跨包值；新增实现进入 `scene`、`geometry`、`surface`、`lighting`、`shadow`、`ao`、`volumetric` 等现有职责包。
- 功能子包不依赖 `render3d.pipeline`、`RenderPipeline` 或 `ForwardPassBuilder`。`postprocess` 不导入 `render3d`；通过输入合同及 pipeline 适配器协作，效果子包不随意互相引用。
- `surface` 按消费者需求生产共享通道，不依赖 AO/雾/后处理实现。`lighting` 消费 shadow 只读计划；`shadow` 接收跨模块灯表，不重新引入双向依赖。
- 热路径沿用已有复用缓冲和有界诊断，不新增无界逐帧集合、同步 GPU 读回或 `glFinish`。只为解决已确认的问题引入缓存/抽象。

## 所有权与事务

- GL 资源操作在同一 GPU owner 线程与 current context 内执行，保持入口校验。每份资源声明 owned/borrowed；宿主附件、IBL 和调用方 recorder 不由借用者销毁。
- RenderGraph 管理其创建的附件；模块管理专属程序、历史和存储。外部导入明确借用，候选代协调 owner 转交；不要把模块私有纹理误写成全由 Graph 拥有。
- 先完整准备并校验候选，再在帧边界发布；发布前失败释放候选自有资源、保留旧代与 effective。候选不能提前关闭借用 owner。
- 发布后旧代退役失败保留新活动代与 effective/revision，通过退役诊断暴露并保留后续清理能力；不伪报回滚。失败控制请求不在普通帧隐式重试。
- resize 同样准备完整候选后提交，旧租约与帧视图不得跨代使用。TAA、GTAO、体积雾等历史只在成功帧提交；失败帧写入不能解释为像素回滚。
- 总开关暂停保留子配置与资源，恢复失效相关历史；功能关闭移除自身执行及不再使用的专属资源，保留其他消费者仍需的共享表面。关闭资源遵循所属 owner 生命周期。

## 配置与渲染合同

- 正式功能接入 `RenderFeaturesControl` / `RenderFeatureSettings` 与统一依赖校验，批量修改一次候选发布；保留旧 `apply*` 兼容入口的状态一致性。控制器不管理 GPU 资源。
- 明确 HDR/LDR、AA 模式、解析雾/体积雾及 VFX 的依赖/互斥，非法组合明确拒绝；请求值、实际活动与不可用原因分别报告，不静默忽略。
- 新 Pass/输入声明格式、坐标与深度约定、颜色空间、采样数、分辨率、jitter/velocity 和时域语义；共享附件通过现有租约/依赖校验，不自行假设兼容。
- 调整图顺序、透明混合、MSAA resolve、历史失效或宿主目标时覆盖直接消费者。独立参考先于正式性能结论；复用领域 Demo 和合同测试，不枚举所有功能开关组合。
- 调试/故障注入默认关闭并限制作用域，不发展成第二套生产配置；修改 shader/资产时同步根指引要求的来源、指纹及换行规则。
