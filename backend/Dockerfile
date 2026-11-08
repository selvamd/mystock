# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Tests run in CI / locally (`mvn verify`); the image build only packages.
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /app mystock && mkdir -p /data /app && chown mystock /data /app
WORKDIR /app
COPY --from=build /src/target/mystock-backend.jar /app/mystock-backend.jar
ENV MYSTOCK_DATA_DIR=/data \
    MYSTOCK_API_PORT=9090 \
    JAVA_TOOL_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED -XX:MaxRAMPercentage=75"
VOLUME ["/data"]
EXPOSE 9090
USER mystock
ENTRYPOINT ["java", "-jar", "/app/mystock-backend.jar"]
CMD ["server"]
