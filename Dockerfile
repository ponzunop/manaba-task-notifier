# Multi-stage Dockerfile for manaba 課題通知システム (Render / Railway / Fly.io 対応)
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw package -DskipTests -B

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
COPY --from=build /app/target/manaba-notifier-*.jar app.jar
RUN mkdir -p /app/data /app/config && chown -R appuser:appgroup /app
USER appuser

ENV SERVER_ADDRESS=0.0.0.0
ENV APP_PUBLIC_MODE=true
ENV TZ=Asia/Tokyo
EXPOSE 8080

# Render (DB_HOST/DB_PORT/DB_NAME) および Railway (postgres:// / postgresql:// DATABASE_URL) の自動 JDBC URL 変換に対応
ENTRYPOINT ["sh", "-c", "if [ -z \"$SPRING_DATASOURCE_URL\" ] && [ -n \"$DB_HOST\" ]; then export SPRING_DATASOURCE_URL=\"jdbc:postgresql://${DB_HOST}:${DB_PORT:-5432}/${DB_NAME}\"; fi; if [ -z \"$SPRING_DATASOURCE_URL\" ] && [ -n \"$DATABASE_URL\" ]; then case \"$DATABASE_URL\" in postgres://*|postgresql://*) stripped=\"${DATABASE_URL#*://}\"; creds=\"${stripped%%@*}\"; hostpart=\"${stripped#*@}\"; export SPRING_DATASOURCE_USERNAME=\"${creds%%:*}\"; export SPRING_DATASOURCE_PASSWORD=\"${creds#*:}\"; export SPRING_DATASOURCE_URL=\"jdbc:postgresql://${hostpart}\"; ;; esac; fi; exec java -Duser.timezone=Asia/Tokyo -XX:MaxRAMPercentage=75.0 -jar /app/app.jar"]
