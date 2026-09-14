# ============================================================
# 后端镜像 —— 多阶段构建
# ============================================================
# 分两个阶段的好处：**最终镜像里不含构建工具**。
#
#   构建阶段用了完整的 Maven + JDK（约 800MB）
#   运行阶段只要 JRE（约 250MB）
#
# 如果写成单阶段，镜像会白白胖胖多出几百 MB，而且把源码、
# Maven 缓存、编译中间产物全带了进去——攻击面也更大。
# ============================================================

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只复制 pom.xml 并单独下载依赖。
# 这样只要 pom.xml 没变，改代码时这一层就能命中缓存，
# 不用重新下载全部依赖（能省一两分钟）。
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

# 再复制源码并打包
COPY src ./src
# -DskipTests：测试在 CI 里跑过了，镜像构建时再跑一遍纯属浪费时间。
# 但这是**有前提的**——CI 必须真的在跑测试，否则就是"反正本地能跑"的自我欺骗。
RUN mvn -B -ntp package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# 从构建阶段只把打好的 jar 拿过来，其余全部丢弃
COPY --from=build /build/target/*.jar app.jar

# 用非 root 用户运行。
# 默认的 root 在容器里虽然被隔离，但一旦容器逃逸就是宿主机 root，
# 而且很多安全扫描器会直接把这当成高危项。多这一行成本为零。
RUN useradd --system --create-home --uid 1001 spring \
    && chown -R spring:spring /app
USER spring

EXPOSE 8080

# 让 JVM 感知容器内存限制。
# 不加这个的话，JDK 会按**宿主机**的内存去算默认堆大小——
# 在内存受限的容器里会被 OOM Killer 干掉。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 用 exec 形式（JSON 数组）而不是 shell 形式。
# shell 形式（ENTRYPOINT java -jar ...）会让 java 变成 shell 的子进程，
# 结果 SIGTERM 发给 shell 而不是 java，容器停止时要等超时才被强杀。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
