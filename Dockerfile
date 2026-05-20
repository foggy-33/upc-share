FROM node:22-alpine AS frontend-build

WORKDIR /src/vue
COPY vue/package*.json ./
RUN npm install
COPY vue/ ./

ARG VITE_CAMPUS_ORIGIN=https://in.upcshare.cn
ARG VITE_PUBLIC_ORIGINS=https://upcshare.cn,https://www.upcshare.cn
ARG VITE_ACCESS_ROUTE_PROBE_PATH=/api/ping
ARG VITE_ACCESS_ROUTE_TIMEOUT_MS=1200
ENV VITE_CAMPUS_ORIGIN=${VITE_CAMPUS_ORIGIN}
ENV VITE_PUBLIC_ORIGINS=${VITE_PUBLIC_ORIGINS}
ENV VITE_ACCESS_ROUTE_PROBE_PATH=${VITE_ACCESS_ROUTE_PROBE_PATH}
ENV VITE_ACCESS_ROUTE_TIMEOUT_MS=${VITE_ACCESS_ROUTE_TIMEOUT_MS}
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build

WORKDIR /src
COPY springboot/pom.xml springboot/pom.xml
RUN mvn -f springboot/pom.xml -q -DskipTests dependency:go-offline
COPY springboot/ springboot/
COPY --from=frontend-build /src/vue/dist/ springboot/src/main/resources/static/
RUN mvn -f springboot/pom.xml -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin appuser \
    && mkdir -p /app/resources /app/data \
    && chown -R appuser:appuser /app

COPY --from=backend-build /src/springboot/target/download-site-1.0.0.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC" \
    RESOURCES_DIR=/app/resources \
    SERVER_PORT=8080

EXPOSE 8080
USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
