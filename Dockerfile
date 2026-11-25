# 后端运行镜像。构建产物是 Spring Boot fat jar，这里只负责「装进去 + 跑起来」。
#
# 为什么用 jammy（Ubuntu 基础）而不是 alpine：
# 应用里有 ImageIO 做图片缩放，alpine 的 musl libc 下偶尔会缺本地库、
# 抛 UnsatisfiedLinkError。用 60MB 体积换掉一类难查的运行时报错，值得。

FROM eclipse-temurin:17-jre-jammy

# curl 只给 healthcheck 用。要区分「端口开着」和「服务真的可用」，
# 后者只能问 /actuator/health；没有 curl 的话健康检查只能测端口通不通，
# 而 Spring 启动到一半卡住时端口是开的、接口全是 500。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# UID 固定成 10001：宿主机 /data/uploads 里的文件属主就是它。
# 不固定的话每次重建镜像可能换一个 uid，宿主机上看就像「文件被陌生用户改了」，
# 排查权限问题时很折磨。
RUN groupadd -r -g 10001 share \
 && useradd -r -u 10001 -g share -s /sbin/nologin share

WORKDIR /app

# 只拷 fat jar。jar 在 CI / 本地用 mvn package 产出，
# 镜像里不做 mvn build —— 构建和运行分离，镜像层不掺 Maven 缓存。
#
# 用通配符而不是写死版本号：项目版本从 0.0.1-SNAPSHOT 升上去时，
# 写死的路径会让构建突然失败，而报错只说 "file not found"。
# target 下只有一个 .jar（repackage 的产物），.jar.original 不匹配 *.jar。
COPY target/*.jar /app/app.jar

# 上传目录由 compose 挂载覆盖，这里先建好并给权限，
# 否则容器以 share 用户启动时写不进去（挂载点继承宿主机权限）
RUN mkdir -p /data/uploads && chown -R share:share /app /data/uploads

USER share
EXPOSE 8080

# MaxRAMPercentage 依赖容器的内存限制（compose 里的 mem_limit）。
# 不设限制时它按宿主机内存算（4G × 70% = 2.8G），堆开太大被 OOM Killer 干掉，
# 现象是「应用突然消失、日志没有任何异常」。
#
# ExitOnOutOfMemoryError：真 OOM 时立刻退出让容器重启，而不是半死不活地
# 一直抛 OutOfMemoryError、每个请求都 500。
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Duser.timezone=Asia/Shanghai", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
