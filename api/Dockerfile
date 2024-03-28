# Build context: repository root (the API needs the parent pom).
# The jar is platform-independent: build it natively, only the runtime image is per-architecture.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY api/pom.xml api/
COPY mockpay/pom.xml mockpay/
RUN mvn -B -ntp -q -pl api -am dependency:go-offline
COPY api/src api/src
RUN mvn -B -ntp -q -pl api -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app && mkdir -p /keys /data/images && chown -R app:app /keys /data
WORKDIR /app
COPY --from=build /src/api/target/wokflow-api.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
