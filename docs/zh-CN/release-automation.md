# 自动发布

LingFrame 以 GitHub 为主发布源。`release/0.4.x` 发布分支上的 CI 全部通过后，发布工作流会检查版本号是否相对上一提交发生变化；只有发生变化时才创建不可变的 `V<version>` Tag 和 GitHub Release。0.4 版本线代号为“含章”，0.4.6 继续沿用该代号。

发布提交必须同时更新 `lingframe-dependencies/pom.xml`、`lingframe-bom/pom.xml`、中英文 CHANGELOG，并在 CHANGELOG 中提供对应的 `V<version>` 条目。工作流会拒绝版本不一致、非稳定语义版本或缺少发布说明的提交。

GitHub Release 创建成功后，工作流会将主分支、发布分支和发布 Tag 同步到 Gitee、GitCode。同步使用 `mirrors` Environment 中的 SSH 凭据：

- `GITEE_SSH_KEY`
- `GITCODE_SSH_KEY`
- `MIRROR_KNOWN_HOSTS`

两个镜像仓库应允许该部署密钥推送，并保护发布 Tag，禁止覆盖已有 Tag。Maven Central 仍按现有 `-Prelease` 流程单独发布；本工作流不重复上传制品。

发布完成后，应将 `release/0.4.x` 合并回 `main`。如果镜像同步失败，可以在 GitHub Actions 中重新运行失败的矩阵任务，不需要重新创建 Release。
