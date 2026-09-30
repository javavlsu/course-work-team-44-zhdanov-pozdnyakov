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
#   ./scripts/run-docker.sh down       # остановить и удалить контейнеры (данные сохраняются)
#   ./scripts/run-docker.sh reset      # остановить и УДАЛИТЬ тома с БД и собранными образами
#   ./scripts/run-docker.sh --skip-build   # не пересобирать артефакты
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

API_URL="${VITE_API_URL:-http://localhost:8080/api}"

# Как вызывать docker: напрямую или через sudo.
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
	echo "[run-docker] нет доступа к docker без sudo — использую sudo (введите пароль)."
	DOCKER="sudo docker"
fi

# Compose v2 (плагин «docker compose», Docker Desktop) или отдельный docker-compose (v1).
if $DOCKER compose version >/dev/null 2>&1; then
	COMPOSE="$DOCKER compose"
elif command -v docker-compose >/dev/null 2>&1; then
	COMPOSE="${DOCKER%docker}docker-compose"
else
	echo "[run-docker] не найден docker compose. Включите WSL integration в Docker Desktop" >&2
	echo "             (Settings → Resources → WSL integration) или установите docker-compose-plugin." >&2
	exit 1
fi

case "${1:-}" in
	down)  exec $COMPOSE down ;;
	reset) exec $COMPOSE down -v ;;
esac

SKIP_BUILD=0
DETACH=""
for arg in "$@"; do
	case "$arg" in
		--skip-build) SKIP_BUILD=1 ;;
		-d|detached)  DETACH="-d" ;;
	esac
done

# Linux-инструменты сборки: системные или установленные в домашний каталог.
# Windows-версии из /mnt/c (mvn, npm) в WSL для сборки не подходят.
find_tools() {
	if ! command -v java >/dev/null 2>&1 || [[ "$(command -v java)" == /mnt/* ]]; then
		for j in "${JAVA_HOME:-}" "$HOME/.local/tools/jdk17" /usr/lib/jvm/java-17-openjdk-amd64; do
			if [ -n "$j" ] && [ -x "$j/bin/java" ]; then export JAVA_HOME="$j"; export PATH="$j/bin:$PATH"; break; fi
		done
	fi
	if ! command -v mvn >/dev/null 2>&1 || [[ "$(command -v mvn)" == /mnt/* ]]; then
		[ -x "$HOME/.local/tools/maven/bin/mvn" ] && export PATH="$HOME/.local/tools/maven/bin:$PATH"
	fi
	if ! command -v node >/dev/null 2>&1 || [[ "$(command -v npm)" == /mnt/* ]]; then
		# shellcheck disable=SC1091
		[ -s "$HOME/.nvm/nvm.sh" ] && source "$HOME/.nvm/nvm.sh" >/dev/null 2>&1 || true
	fi
	local missing=""
	for t in java mvn node npm; do
		if ! command -v "$t" >/dev/null 2>&1 || [[ "$(command -v "$t")" == /mnt/* ]]; then missing="$missing $t"; fi
	done
	if [ -n "$missing" ]; then
		echo "[run-docker] не найдены Linux-версии:$missing" >&2
		echo "             Установите: sudo apt install openjdk-17-jdk maven nodejs npm" >&2
		echo "             или запустите с --skip-build, если jar и dist уже собраны." >&2
		exit 1
	fi
}

if [ "$SKIP_BUILD" -eq 0 ]; then
	find_tools
	echo "[run-docker] (1/2) сборка backend (mvn package)…"
	( cd backend && mvn -q package -DskipTests )

	echo "[run-docker] (2/2) сборка frontend (npm run build, API=$API_URL)…"
	( cd frontend && npm install --silent && VITE_API_URL="$API_URL" npm run build )
fi

echo "[run-docker] $COMPOSE up --build $DETACH"
exec $COMPOSE up --build $DETACH
