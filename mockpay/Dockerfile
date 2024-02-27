# Build context: repository root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY api/pom.xml api/
COPY mockpay/pom.xml mockpay/
RUN mvn -B -ntp -q -pl mockpay -am dependency:go-offline
COPY mockpay/src mockpay/src
RUN mvn -B -ntp -q -pl mockpay -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /src/mockpay/target/wokflow-mockpay.jar app.jar
USER app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8090/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
