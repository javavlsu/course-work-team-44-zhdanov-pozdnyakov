# Образ запуска бэкенда.
#
# В этой среде Docker-демон (WSL2) не резолвит DNS внутри build-шагов, поэтому
# артефакт собирается на ХОСТЕ (mvn package), а контейнер его только запускает.
# Сборку артефакта выполняет scripts/run-docker.sh перед `docker compose`.
#
# Полноценный multi-stage вариант (сборка внутри контейнера) сохранён в
# Dockerfile.multistage — он работает в средах с рабочим DNS в build.
# RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

FROM eclipse-temurin:17-jre-jammy

RUN rm -f /etc/apt/apt.conf.d/docker-clean && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
# RUN rm -f /etc/apt/apt.conf.d/docker-clean && \
#     apt-get update && \
#     apt-get install -y --no-install-recommends curl ca-certificates && \
#     rm -rf /var/lib/apt/lists/*

# ... rest of your app setup and HEALTHCHECK
HEALTHCHECK --interval=60s --timeout=360s --start-period=180s --retries=30 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
# HEALTHCHECK --interval=30s --timeout=3s \
#   CMD curl -f http://localhost:8080/actuator/health || exit 1

WORKDIR /app

# bash/coreutils присутствуют в jammy. mkfs.ext4 в образе нет — обёртка
# luckfox_build.sh в этом случае автоматически собирает .img как tar-артефакт.
COPY target/image-builder-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
