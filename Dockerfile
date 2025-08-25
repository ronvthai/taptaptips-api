# ---- build stage ----
FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app

# cache dependencies
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

# copy source and build
COPY . .
RUN mvn -q -DskipTests clean package

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*SNAPSHOT*.jar app.jar

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# Port Render (or other platforms) injects as $PORT
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
