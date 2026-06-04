# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY inquery-server-domain/  inquery-server-domain/
COPY inquery-server-tools/   inquery-server-tools/
COPY inquery-server-web/     inquery-server-web/
COPY inquery-spi/            inquery-spi/
COPY inquery-plugins/        inquery-plugins/
COPY inquery-server-web-start/ inquery-server-web-start/
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/inquery-server-web-start/target/inquery-server-web-start.jar app.jar

EXPOSE 10821

ENTRYPOINT ["java", \
  "--add-opens=java.base/java.nio=ALL-UNNAMED", \
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
