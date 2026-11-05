# Java 业务层镜像。
#
# ★ 为什么不在镜像里跑 mvn（多阶段）：本仓库的构建产物已经在 CI/本机验过，
#   而 Maven 阶段要额外下 ~200MB 依赖并把构建时间从 10 秒拉到几分钟。
#   代价如实说：**镜像里的 jar 必须先生成**（`mvn package`），
#   所以 compose 的 README 要写清"先构建再 up"。
#   如果哪天要交给别人一条命令跑，就换成多阶段（把 mvn 也装进来）。
FROM eclipse-temurin:17-jre

# 时区：审计表的 created_at 与配额"当天"都按 Asia/Shanghai 算。
# 不设的话容器是 UTC，配额会在北京时间早上 8 点"跨天"——看起来像 bug。
ENV TZ=Asia/Shanghai

WORKDIR /app
COPY target/policy-desk-api-*.jar /app/app.jar

# 非 root：容器被拿下的影响面小一点，也是大多数扫描器的基线要求
RUN useradd -r -u 1001 appuser
USER appuser

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
