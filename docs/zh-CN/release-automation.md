# 自动发布

LingFrame 以 GitHub 为主发布源。完整质量检查只在 PR 上作为合并门禁执行；开发分支可以通过 `CI` 工作流的 `workflow_dispatch` 手动选择 `full` 或 `performance` 套件。合并到 `main` 后只运行 `Main Smoke` 合并后冒烟检查，不重复执行 PR 的完整矩阵。

`Main Smoke` 成功后会触发发布工作流。工作流检查当前提交相对父提交是否发生稳定版本变化；只有发布意图明确、版本号和中英文 CHANGELOG 均有效时，才创建不可变的 `V<version>` Tag、GitHub Release，并最后创建或推进 `v<version>-release` 稳定发布分支。普通功能合并不会自动发版。0.4 版本线代号为“含章”，0.4.7 继续沿用该代号。

如果需要补同步历史 Release，可在 GitHub Actions 手动运行 `Release and Metadata Sync`，选择 `main` 分支，在 `versions` 中填写一个或多个版本（用逗号或换行分隔，例如 `0.4.5,0.4.6,0.4.7`），并在 `platform` 中选择 `all`、`gitee` 或 `gitcode`。手动路径只读取 GitHub 上已存在的对应 Release，并把名称、说明和目标提交同步到选定平台；不会重新创建 GitHub Tag/Release，也不会要求当前源码版本与历史版本一致。平台上同一 Tag 的 Release 已存在时会跳过创建。

`main` 应启用分支保护，禁止直接 push，并要求 PR 检查通过后才能合并。PR 的必需检查至少包括 SB2/SB3 构建测试、示例集成冒烟和对应覆盖率门禁；`Main Smoke` 属于合并后的发布前置检查，不替代 PR 门禁。

发布提交必须同时更新 `lingframe-dependencies/pom.xml`、`lingframe-bom/pom.xml`、中英文 CHANGELOG，并在 CHANGELOG 中提供对应的 `V<version>` 条目。工作流会拒绝版本不一致、非稳定语义版本或缺少发布说明的提交。

代码仓库的分支和 Tag 由 Gitee、GitCode 官方镜像同步设置负责。GitHub Release 创建成功后，工作流只通过两个平台的 Release API 同步发布元数据。请在 `mirrors` Environment 中配置：

- `GITEE_TOKEN`
- `GITCODE_TOKEN`

两个令牌需要具备创建仓库 Release 的权限。工作流发现同一 Tag 的 Release 已存在时会跳过创建，避免重复发布。Maven Central 仍按现有 `-Prelease` 流程单独发布；本工作流不重复上传制品。

发布分支在 Release 创建之后生成，不需要再合并回 `main`。如果元数据同步失败，可以重新运行工作流，并在 `platform` 中只选择失败的平台；不需要重新创建 GitHub Release。
