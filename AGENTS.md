# Haikalat 仓库指引

Java 21 / LWJGL / OpenGL 4.6 渲染框架。生产代码在 `src/main`，示例在 `src/demo`，测试在 `src/test`，构建任务在 `gradle`；临时日志、截图和探查脚本放 `build/`。

## 开始工作

- 先检查 `git status`、相关 staged/unstaged 差异及未跟踪文件，保留已有工作。先用 `rg` 在相关目录定位，再按依赖扩大范围。
- 用户明确要求和运行环境的更高优先级指令优先。局部 `AGENTS.md` 补充本文件，在其作用域内优先；不要为普通可逆修改增加审批步骤。
- 按任务阅读下表，不需要遍历所有计划或重跑所有历史命令。

| 修改范围 | 补充指引 |
|---|---|
| Gradle、Wrapper、依赖锁、`verify.ps1` 或 CI，包括根 `build.gradle` / `settings.gradle` | [构建指引](gradle/AGENTS.md) |
| 新增、修改、合并或删除测试，包括生产改动带来的测试调整 | [测试指引](src/test/java/AGENTS.md) |
| Render3D；关联的 postprocess、渲染 shader 或共享表面协议变更 | [渲染指引](src/main/java/com/kaleblangley/haikalat/subsystems/render3d/AGENTS.md) |

## 实现边界

- 依赖方向保持 `subsystems/runtime → core → backend`；原始 GL 调用留在 backend 或既有受控适配层，纯逻辑域保持无 GL。
- 使用最小必要可见性和已有领域抽象。新抽象需要真实调用方或生命周期职责；不因未来可能用到就增加 manager、factory、接口或通用框架。
- 新增/迁移 public 顶层类型时同步[公共 API 清单与迁移记录](docs/architecture/public-api/README.md)。不要为跨包调用绕过 internal 边界。
- 按风险与行为合同验证，优先扩展已有领域测试。新增生产类不要求同名测试类；简单 getter、默认值、无分支委托不机械补测试，公开兼容或真实缺陷回归除外。
- 新渲染技术先在已有计划或变更说明补充简短[技术卡](docs/planning/v0.27.5-agent-and-test-governance.md#8-未来实时渲染技术提案卡)：输入输出、所属模块、资源生命周期、独立参考、成本与验证范围；不按技术数量复制 Demo 和 Gradle 任务。

## 选择验证

从仓库根目录运行，以下按需选择，不依次叠加。Windows 使用 `.\gradlew.bat`，其他平台使用 `./gradlew`；`verify.ps1` 当前面向 Windows。

| 目的 | 入口与限制 |
|---|---|
| 文档、指引 | 核对事实、链接与差异；无需编译或 GL |
| 定向 JVM | `.\gradlew.bat test --tests "*RenderFeatureControlTest"`，替换为受影响类/方法 |
| 定向真实 GL | `.\gradlew.bat glSmoke --tests "*RenderFeatureControlGlTest"`，需桌面和 OpenGL 4.6，核对实际执行与所需读回 |
| Render3D 聚焦 | `.\verify.ps1 -Mode Jvm` 或 `.\verify.ps1`；固定范围，默认总时限 300 秒 |
| UI 消费者 | `.\verify.ps1 -Mode UiConsumers`；更窄模式见测试指南 |
| 完整无桌面检查 | `.\gradlew.bat check`，比日常定向验证更广 |

- 命令、报告位置和范围以[测试指南](docs/guides/testing.md)与实际任务定义为准；范围升级和证据复用遵循[验收规范](docs/guides/focused-acceptance-policy.md)。当前没有自动按 diff 选测。
- 默认增量构建；不要惯例性使用 `clean`、`--rerun-tasks`。无过滤的 `glSmoke` 会执行含 JVM 的完整测试集；`check` 已去掉重复的过滤 JUnit 子集，但仍是完整无桌面检查，不能称为最小 smoke。默认 GL 叶子是 `glLeafVerification`。
- 长测、正式性能和完整视觉/发布矩阵按明确需求单独选择，相关任务需 `-PextendedVerification=true`。普通功能签收不自动追加 benchmark 或全部 `local*Verification`。
- 默认零自动重试。失败先保留候选身份、case、日志和耗时；可恢复的启动/环境问题最多一次定向复跑，同故障转诊断。修复后重跑受影响用例与直接消费者。
- 超时、空选择、必需用例未执行或意外跳过不能记 PASS。`UP-TO-DATE`、历史复用、新执行分别说明；功能通过、性能结论与发布就绪分别报告。

## 资源、文档与交付

- 区分 production/demo/reference 资源，保留来源、许可和指纹；常规资产见 [assets.tsv](docs/licenses/assets.tsv)，VFX 按[包级来源规则](docs/licenses/vfx-textures.md)。遵守 `.gitattributes`，不手改 `build/generated`，不大面积转换换行。
- 参考图、数值阈值和 `config/gl-debug-policy.tsv` 的更新必须有独立依据；不得为了通过检查放宽正确性断言、吞掉 GL 错误或把当前输出直接当真值。
- 当前能力与合同从[文档入口](docs/README.md)查阅；版本计划、性能和发布报告保留原候选身份。缺失的 `build/` 证据不能计为当前通过。
- `build.gradle` 是构建版本来源，版本/依赖升级按任务范围进行。保留无关修改及暂存状态；提交只包含本次明确交付，不顺手混入其他工作。
- 交付说明写清改变了什么、验证命令与结果、复用或未测范围、剩余问题；不要以新增测试数量、文档数量或脚本行数代替完成证据。
