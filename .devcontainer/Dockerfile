FROM mcr.microsoft.com/devcontainers/java:1-17

# 额外安装JDK8（LingFrame遗留系统兼容）
RUN apt-get update && apt-get install -y openjdk-8-jdk \
    && update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-8-openjdk-amd64/bin/java 1 \
    && update-alternatives --install /usr/bin/java java /usr/lib/jvm/java-17-openjdk-amd64/bin/java 2 \
    && update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/bin/java