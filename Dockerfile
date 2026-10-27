# syntax=docker/dockerfile:1
# 一份 Dockerfile 双产物（构建上下文 = 项目根）：
#   docker build --target server .   → 后端镜像（含渲染核心 + Chromium + 中文字体）
#   docker build --target web .      → 前端镜像（nginx + /api 反代）
# 日常无需手动 build：根目录 `docker compose up -d --build` 一键全栈

######## 后端 jar ########
FROM maven:3.9-eclipse-temurin-21 AS server-build
# 复用 renderer 的 settings.xml（仅阿里云镜像，加速容器内依赖解析）
COPY report-renderer/settings.xml /root/.m2/settings.xml
# 预置本地仓库（tools/m2-repo 已含全部后端依赖）：容器内逐个从 aliyun 拉几百个小构件太慢，
# 少量缺失件由镜像里的 aliyun 兜底
COPY tools/m2-repo /root/.m2/repository
WORKDIR /build
COPY report-server/ ./
RUN mvn -B package -DskipTests

######## 渲染核心（RenderService 运行时 fork 用：target/classes + target/lib/*.jar） ########
FROM maven:3.9-eclipse-temurin-21 AS renderer-build
COPY report-renderer/settings.xml /root/.m2/settings.xml
# 预置本地仓库（含全部渲染依赖）：冷构建免从 aliyun 逐个拉几百个小构件（易被网络抖动挂死），缺失件由 aliyun 兜底
COPY tools/m2-repo /root/.m2/repository
WORKDIR /build
COPY report-renderer/pom.xml ./
COPY report-renderer/src ./src
# copy-dependencies 默认输出 target/dependency，必须显式指到 target/lib（渲染 classpath 约定）
RUN mvn -q package dependency:copy-dependencies -DoutputDirectory=target/lib -DskipTests

######## target: server 运行镜像 ########
# 基底 = Playwright 官方 Java 镜像：浏览器版本(1.49.0)与 pom 一致 + 全部系统依赖
FROM mcr.microsoft.com/playwright/java:v1.49.0-noble AS server
# 镜像自带 JDK17，本项目字节码 21 → 叠加 temurin 21（取自本地 maven 构建镜像内置的
# /opt/java/openjdk，免拉 eclipse-temurin:21-jre-noble 基镜像——国内网络拉 docker.io
# 基镜像易僵死；代价是 JDK 比 JRE 约大 200MB，换来冷构建零外网依赖）
COPY --from=maven:3.9-eclipse-temurin-21 /opt/java/openjdk /opt/jdk21
ENV JAVA_HOME=/opt/jdk21 PATH="/opt/jdk21/bin:${PATH}"
# 中文字体：缺失则 PDF 全方块（apt 切清华 TUNA 源，国内构建快且稳——阿里云 CDN
# 大文件 deb 偶发连接失败；兼容 noble 的 ubuntu.sources 新格式与旧 sources.list，
# 其一不存在时 sed 报错由 || true 兜底）。apt 重试抗宿主网络间歇抖动
# （resolv.conf 在 BuildKit 容器内只读，无法直钉公共 DNS，勿再试）
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.aliyun.com/ubuntu|g; s|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' \
      /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list 2>/dev/null || true \
 && apt-get -o Acquire::Retries=5 update \
 && apt-get install -y --no-install-recommends -o Acquire::Retries=5 fontconfig fonts-noto-cjk \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server-build /build/target/*.jar server.jar
COPY --from=renderer-build /build/target/classes renderer/target/classes
COPY --from=renderer-build /build/target/lib renderer/target/lib
ENV TZ=Asia/Shanghai \
    AISCHOOL_RENDER_RENDERER_HOME=/app/renderer \
    AISCHOOL_RENDER_WORK_DIR=/app/render-work
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "server.jar"]

######## 前端静态构建 ########
FROM node:20-alpine AS web-build
WORKDIR /web
COPY report-web/package.json report-web/package-lock.json ./
# npmmirror 加速国内构建；npm 重试参数抗 CDN 抖动（ECONNRESET/EIDLETIMEOUT），
# BuildKit 缓存挂载让失败重试从已下载的包续跑（resolv.conf 容器内只读，无法直钉 DNS）
RUN --mount=type=cache,target=/root/.npm \
    npm config set registry https://registry.npmmirror.com \
 && npm config set fetch-retries 5 fetch-retry-mintimeout 20000 fetch-retry-maxtimeout 120000 fetch-timeout 600000 \
 && npm ci
COPY report-web/ ./
RUN npm run build

######## target: web 运行镜像 ########
FROM nginx:alpine AS web
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web-build /web/dist /usr/share/nginx/html
EXPOSE 80
