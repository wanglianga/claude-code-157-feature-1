# 语法要求: Docker Compose / docker build 一键构建
# 多阶段: Maven 构建 -> JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# 先拉依赖(利用构建缓存)
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/escort-pass-service-1.0.0.jar /app/app.jar
EXPOSE 8080
# Flyway 启动时自动建表/迁移
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
