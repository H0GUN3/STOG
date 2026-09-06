FROM gradle:9.5-jdk17 AS build

WORKDIR /workspace
COPY --chown=gradle:gradle backend/settings.gradle.kts backend/build.gradle.kts ./
COPY --chown=gradle:gradle backend/src ./src

RUN chown -R gradle:gradle /workspace
USER gradle
RUN gradle --no-daemon --no-scan bootJar

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/build/libs/*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
