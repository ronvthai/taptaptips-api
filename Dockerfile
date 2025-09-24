# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
# Build with Maven wrapper if present; else install Maven
RUN if [ -f ./mvnw ]; then \
      chmod +x ./mvnw && ./mvnw -q -DskipTests package; \
    else \
      apt-get update && apt-get install -y maven && mvn -q -DskipTests package; \
    fi
# Normalize to a single jar path
RUN set -e; \
    JAR="$(ls -1 target/*.jar | head -n1)"; \
    test -n "$JAR" || (echo "No jar in target/"; exit 2); \
    cp "$JAR" /app/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar /app/app.jar
COPY render-start.sh /app/render-start.sh
RUN chmod +x /app/render-start.sh
ENV PORT=8080
CMD ["/app/render-start.sh"]
