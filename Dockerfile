# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .

# Build with whichever tool is present
RUN if [ -f ./mvnw ]; then \
      chmod +x ./mvnw && ./mvnw -q -DskipTests package; \
    elif [ -f ./gradlew ]; then \
      chmod +x ./gradlew && ./gradlew -q -Dorg.gradle.daemon=false -x test bootJar || ./gradlew -q -Dorg.gradle.daemon=false -x test build; \
    else \
      # Fallback to plain Maven if no wrappers exist
      apt-get update && apt-get install -y maven && mvn -q -DskipTests package; \
    fi

# Normalize the output to a single file /app/app.jar
RUN set -e; \
    JAR=""; \
    if [ -d target ]; then \
      JAR="$(ls -1 target/*.jar | head -n1)"; \
    fi; \
    if [ -z "$JAR" ] && [ -d build/libs ]; then \
      JAR="$(ls -1 build/libs/*.jar | head -n1)"; \
    fi; \
    if [ -z "$JAR" ]; then \
      echo "No jar found in target/ or build/libs/"; exit 2; \
    fi; \
    cp "$JAR" /app/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar /app/app.jar

# (Optional) if you rely on render-start.sh to translate DATABASE_URL -> SPRING_DATASOURCE_URL, keep it:
# COPY render-start.sh /app/render-start.sh
# RUN chmod +x /app/render-start.sh
# ENV PORT=8080
# CMD ["/app/render-start.sh"]

# Or, run directly if your app handles DB env itself:
ENV PORT=8080
CMD ["java","-Dserver.port=${PORT}","-jar","/app/app.jar"]
