FROM hub.gitverse.ru/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM hub.gitverse.ru/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/space-rescue-1.0.0.jar /app/app.jar
EXPOSE 8000
CMD ["java", "-jar", "/app/app.jar"]
