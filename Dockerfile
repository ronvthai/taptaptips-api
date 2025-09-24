# Build stage (use your tool: maven or gradle)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
# If Maven repo:
# RUN ./mvnw -q -DskipTests package
# If Gradle repo:
RUN ./gradlew -q -Dorg.gradle.daemon=false -x test bootJar || ./gradlew -q -Dorg.gradle.daemon=false -x test build

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
# Copy built jars (both Gradle and Maven patterns covered)
COPY --from=build /app/build/libs/*.jar /app/build/libs/ 2>/dev/null || true
COPY --from=build /app/target/*.jar    /app/target/       2>/dev/null || true
COPY render-start.sh /app/render-start.sh
RUN chmod +x /app/render-start.sh
ENV PORT=8080
CMD ["/app/render-start.sh"]
