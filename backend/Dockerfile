# Образ запуска бэкенда.
#
# В этой среде Docker-демон (WSL2) не резолвит DNS внутри build-шагов, поэтому
# артефакт собирается на ХОСТЕ (mvn package), а контейнер его только запускает.
# Сборку артефакта выполняет scripts/run-docker.sh перед `docker compose`.
#
# Полноценный multi-stage вариант (сборка внутри контейнера) сохранён в
# Dockerfile.multistage — он работает в средах с рабочим DNS в build.
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# bash/coreutils присутствуют в jammy. mkfs.ext4 в образе нет — обёртка
# luckfox_build.sh в этом случае автоматически собирает .img как tar-артефакт.
COPY target/image-builder-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
