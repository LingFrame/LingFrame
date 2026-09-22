FROM mcr.microsoft.com/devcontainers/java:1-17-bookworm

# LingFrame 默认构建矩阵为 Spring Boot 2 / JDK 8；JDK 17 供 -Pspring-boot3 使用。
# 注意：Debian 12+（bookworm）官方仓库已移除 openjdk-8-jdk，apt 无法安装 JDK 8；
# devcontainers java 镜像的 JDK 统一由 SDKMAN 管理，故经 SDKMAN 安装 Temurin 8 并设为默认。

# 1) 以 vscode 用户经 SDKMAN 安装最新 Temurin 8 并设为默认 JDK
#    （版本号动态解析，避免写死补丁版本导致镜像升级后构建失败）
RUN su vscode -c "bash -lc 'source /usr/local/sdkman/bin/sdkman-init.sh && \
    V8=\$(sdk list java 2>/dev/null | grep -oE \"8\\.0\\.[0-9]+-tem\" | sort -V | tail -1) && \
    echo \"Installing JDK 8: \${V8}\" && \
    sdk install java \${V8} && sdk default java \${V8}'"

# 2) 确保 Maven 可用（镜像未自带时经 SDKMAN 安装）
RUN su vscode -c "bash -lc 'source /usr/local/sdkman/bin/sdkman-init.sh && command -v mvn >/dev/null 2>&1 || sdk install maven'"

# 3) 建立稳定的 /usr/lib/jvm 软链，供 devcontainer.json 的 java.configuration.runtimes 引用
RUN mkdir -p /usr/lib/jvm && \
    JDK8=\$(ls -d /usr/local/sdkman/candidates/java/8* 2>/dev/null | head -1) && \
    JDK17=\$(ls -d /usr/local/sdkman/candidates/java/17* 2>/dev/null | head -1) && \
    ln -sfn \"\${JDK8}\" /usr/lib/jvm/java-8-openjdk-amd64 && \
    ln -sfn \"\${JDK17}\" /usr/lib/jvm/java-17-openjdk-amd64
