# Образ фронтенда.
#
# В этой среде Docker-демон (WSL2) не резолвит DNS внутри build-шагов, поэтому
# SPA собирается на ХОСТЕ (npm run build → dist/), а контейнер раздаёт статику
# встроенным Node-сервером server.mjs (без установки пакетов из сети).
# Сборку dist выполняет scripts/run-docker.sh перед `docker compose`.
#
# Полноценный multi-stage вариант сохранён в Dockerfile.multistage.
FROM node:18-slim
WORKDIR /app
COPY dist ./dist
COPY server.mjs ./
EXPOSE 5173
CMD ["node", "server.mjs"]
