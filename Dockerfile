# Build the jar first: ./mvnw package (Jenkins does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user: Kubernetes can then check runAsNonRoot against the image.
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin app

WORKDIR /app
COPY target/bank-open-api-*.jar /app/bank-open-api.jar
USER 10001:10001

EXPOSE 8090
# Heap from the container's memory limit; 60 % leaves room for metaspace and threads in a 512 MiB pod.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/bank-open-api.jar"]
