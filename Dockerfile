# 多阶段构建: Maven 编译打包 -> JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/escort-pass-service-1.0.0.jar app.jar
EXPOSE 8080
ENV DB_URL=jdbc:postgresql://postgres:5432/escort \
    DB_USER=escort \
    DB_PASSWORD=escort \
    ESCORT_KAFKA_ENABLED=false
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
