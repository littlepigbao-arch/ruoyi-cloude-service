# 基础镜像
FROM  maven:3.9.11-eclipse-temurin-8-alpine
# author
LABEL org.opencontainers.image.authors="ruoyi"

# 挂载目录
VOLUME /home/ruoyi
# 创建目录
RUN mkdir -p /home/ruoyi
# 指定路径
WORKDIR /home/ruoyi
# 复制jar文件到路径
COPY ./jar/ruoyi-modules-job.jar /home/ruoyi/ruoyi-modules-job.jar
# 启动定时任务服务
ENTRYPOINT ["java","-jar","ruoyi-modules-job.jar"]
