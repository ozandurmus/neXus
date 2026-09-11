FROM node:24.21.0-bookworm-slim AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY . .
COPY --from=frontend /workspace/frontend/dist /workspace/frontend/dist
RUN gradle --no-daemon :service:bootJar

FROM gcr.io/distroless/java21-debian12@sha256:b59ec1cb8268492a5cdc45391a825ad52ea5ef6bb594287c2b3bcc5a93dda7a9
WORKDIR /app
COPY --from=build /workspace/service/build/libs/*.jar /app/ui2.jar
COPY --from=frontend /workspace/frontend/dist /app/static
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/ui2.jar"]
CMD ["service"]
