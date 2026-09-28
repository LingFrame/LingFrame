# 自动发布

LingFrame 以 GitHub 为主发布源。完整质量检查只在 PR 上作为合并门禁执行；开发分支可以通过 `CI` 工作流的 `workflow_dispatch` 手动选择 `full` 或 `performance` 套件。合并到 `main` 后只运行 `Main Smoke` 合并后冒烟检查，不重复执行 PR 的完整矩阵。

`Main Smoke` 成功后会触发发布工作流。工作流检查当前提交相对父提交是否发生稳定版本变化；只有发布意图明确、版本号和中英文 CHANGELOG 均有效时，才创建不可变的 `V<version>` Tag、GitHub Release，并最后创建或推进 `v<version>-release` 稳定发布分支。普通功能合并不会自动发版。0.4 版本线代号为“含章”，0.4.7 继续沿用该代号。

如果 Release、Tag 或稳定分支因故被删除，需要在 GitHub Actions 手动运行 `Release and Metadata Sync`，选择 `main` 分支并填写目标稳定版本。这个入口只用于恢复已存在版本的发布物，仍会从当前源码和 CHANGELOG 重新校验版本，并在 Release 成功后创建稳定发布分支；正常版本发布仍只走 `Main Smoke` 自动触发路径。

`main` 应启用分支保护，禁止直接 push，并要求 PR 检查通过后才能合并。PR 的必需检查至少包括 SB2/SB3 构建测试、示例集成冒烟和对应覆盖率门禁；`Main Smoke` 属于合并后的发布前置检查，不替代 PR 门禁。

发布提交必须同时更新 `lingframe-dependencies/pom.xml`、`lingframe-bom/pom.xml`、中英文 CHANGELOG，并在 CHANGELOG 中提供对应的 `V<version>` 条目。工作流会拒绝版本不一致、非稳定语义版本或缺少发布说明的提交。

代码仓库的分支和 Tag 由 Gitee、GitCode 官方镜像同步设置负责。GitHub Release 创建成功后，工作流只通过两个平台的 Release API 同步发布元数据。请在 `mirrors` Environment 中配置：

- `GITEE_TOKEN`
- `GITCODE_TOKEN`

两个令牌需要具备创建仓库 Release 的权限。工作流发现同一 Tag 的 Release 已存在时会跳过创建，避免重复发布。Maven Central 仍按现有 `-Prelease` 流程单独发布；本工作流不重复上传制品。

发布分支在 Release 创建之后生成，不需要再合并回 `main`。如果元数据同步失败，可以在 GitHub Actions 中重新运行失败的平台任务，不需要重新创建 GitHub Release。
