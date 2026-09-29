#!/usr/bin/env bash
#
# run-docker.sh — запуск всего стека (db + backend + frontend) через docker compose.
#
# В этой среде Docker не имеет DNS внутри build-шагов, поэтому артефакты (jar и dist)
# собираются на ХОСТЕ, а контейнеры их только запускают. Скрипт:
#   1) собирает backend (mvn package) и frontend (npm run build);
#   2) поднимает docker compose (сам определяет, нужен ли sudo).
#
# Использование:
#   ./scripts/run-docker.sh            # собрать артефакты, собрать образы и запустить
#   ./scripts/run-docker.sh -d         # то же, но в фоне (detached)
#   ./scripts/run-docker.sh down       # остановить и удалить контейнеры
#   ./scripts/run-docker.sh --skip-build   # не пересобирать артефакты
#
set -euo pipefail
sudo docker-compose down -v
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

API_URL="${VITE_API_URL:-http://localhost:8080/api}"

# Как вызывать docker: напрямую или через sudo.
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
	echo "[run-docker] нет доступа к docker без sudo — использую sudo (введите пароль)."
	DOCKER="sudo docker"
fi

if [ "${1:-}" = "down" ]; then
	exec $DOCKER compose down
fi

SKIP_BUILD=0
DETACH=""
for arg in "$@"; do
	case "$arg" in
		--skip-build) SKIP_BUILD=1 ;;
		-d|detached)  DETACH="-d" ;;
	esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
	echo "[run-docker] (1/2) сборка backend (mvn package)…"
	( cd backend && mvn -q package -DskipTests)

	echo "[run-docker] (2/2) сборка frontend (npm run build, API=$API_URL)…"
	( cd frontend && npm install --silent && VITE_API_URL="$API_URL" npm run build )
fi

echo "[run-docker] docker-compose up --build $DETACH"
exec docker-compose up --build $DETACH
