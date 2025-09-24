# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .

# Try Maven wrapper, then Gradle wrapper, then plain Maven (installing it)
RUN if [ -f ./mvnw ]; then \
      chmod +x ./mvnw && ./mvnw -q -DskipTests package; \
    elif [ -f ./gradlew ]; then \
      chmod +x ./gradlew && ./gradlew -q -Dorg.gradle.daemon=false -x test bootJar || ./gradlew -q -Dorg.gradle.daemon=false -x test build; \
    else \
      apt-get update && apt-get install -y maven && mvn -q -DskipTests package; \
    fi

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# support either Gradle or Maven output so render-start.sh can find it
COPY --from=build /app/build/libs/*.jar /app/build/libs/ 2>/dev/null || true
COPY --from=build /app/target/*.jar    /app/target/       2>/dev/null || true
COPY render-start.sh /app/render-start.sh
RUN chmod +x /app/render-start.sh
ENV PORT=8080
CMD ["/app/render-start.sh"]
