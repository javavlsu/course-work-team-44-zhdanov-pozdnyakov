#!/usr/bin/env bash
#
# luckfox_build.sh — обёртка оркестрации сборки Linux-образа для Luckfox Pico.
#
# Вызывается backend-ом (BuildService) для одного заказа на сборку.
# Работает в двух режимах:
#
#   1. Реальная сборка SDK  (LUCKFOX_REAL_BUILD=1):
#        запускает luckfox-pico/build.sh lunch <board> && build.sh,
#        затем копирует получившийся output/image/*.img в выходной файл.
#        Требует развёрнутого SDK (sysdrv/source/...) и кросс-тулчейна.
#
#   2. Симуляция (по умолчанию, для прототипа):
#        собирает реальный .img-артефакт (ext4 rootfs с манифестом и списком
#        выбранных пакетов). Позволяет проверить весь конвейер без многочасовой
#        кросс-компиляции.
#
# Использование:
#   luckfox_build.sh --id <imageId> --name <name> --distro <distro> \
#       --board <board> --components "pkg1,pkg2,..." --out <path/to.img>
#
# Коды возврата: 0 — успех, !=0 — ошибка сборки (backend пометит образ FAILED).

set -euo pipefail

# ---------------------------------------------------------------- параметры ---
IMAGE_ID=""
IMAGE_NAME="image"
DISTRO="buildroot"
BOARD="RV1103_Luckfox_Pico"
COMPONENTS=""
OUT=""

while [[ $# -gt 0 ]]; do
	case "$1" in
		--id)         IMAGE_ID="$2"; shift 2 ;;
		--name)       IMAGE_NAME="$2"; shift 2 ;;
		--distro)     DISTRO="$2"; shift 2 ;;
		--board)      BOARD="$2"; shift 2 ;;
		--components) COMPONENTS="$2"; shift 2 ;;
		--out)        OUT="$2"; shift 2 ;;
		*) echo "[luckfox_build] неизвестный аргумент: $1" >&2; exit 2 ;;
	esac
done

if [[ -z "$OUT" ]]; then
	echo "[luckfox_build] не задан --out" >&2
	exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_DIR="${LUCKFOX_SDK_DIR:-$REPO_ROOT/luckfox-pico}"

# Buildroot (внутри SDK) отказывается собираться, если в $PATH есть элементы с
# пробелами/табами/переводами строк. В WSL в PATH подмешиваются Windows-пути
# (/mnt/c/Program Files/...). Удаляем такие элементы — линуксовой сборке они не нужны.
PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vE '[[:space:]]' | paste -sd:)"
export PATH

mkdir -p "$(dirname "$OUT")"

log() { echo "[luckfox_build] $*"; }

log "Заказ #$IMAGE_ID | образ='$IMAGE_NAME' | дистрибутив=$DISTRO | плата=$BOARD"
log "Компоненты: ${COMPONENTS:-<нет>}"

# ----------------------------------------------------------- реальный режим ---
real_build() {
	log "Режим: РЕАЛЬНАЯ сборка SDK"
	if [[ ! -x "$SDK_DIR/build.sh" ]]; then
		log "ОШИБКА: $SDK_DIR/build.sh не найден/не исполняемый"
		return 1
	fi
	if [[ ! -d "$SDK_DIR/sysdrv/source" ]]; then
		log "ОШИБКА: исходники SDK ($SDK_DIR/sysdrv/source) отсутствуют — разверните SDK."
		return 1
	fi

	pushd "$SDK_DIR" >/dev/null
	log "lunch -> $BOARD"
	# Выбор конфигурации платы по подстроке имени (SD_CARD-Buildroot по умолчанию).
	local cfg
	cfg="$(ls project/cfg/BoardConfig_IPC/BoardConfig-SD_CARD-Buildroot-*"$BOARD"*.mk 2>/dev/null | head -n1 || true)"
	[[ -z "$cfg" ]] && cfg="$(ls project/cfg/BoardConfig_IPC/BoardConfig-*"$BOARD"*.mk 2>/dev/null | head -n1 || true)"
	if [[ -z "$cfg" ]]; then
		log "ОШИБКА: не найден BoardConfig для '$BOARD'"
		popd >/dev/null
		return 1
	fi
	log "BoardConfig: $cfg"
	ln -rfs "$cfg" "$SDK_DIR/.BoardConfig.mk"

	# Полная сборка: uboot + kernel + rootfs + recovery + прошиваемый update.img.
	./build.sh
	./build.sh updateimg || log "updateimg пропущен"
	popd >/dev/null

	# Предпочитаем прошиваемый update.img, иначе — самый свежий .img.
	local img
	if [[ -f "$SDK_DIR/output/image/update.img" ]]; then
		img="$SDK_DIR/output/image/update.img"
	else
		img="$(ls -t "$SDK_DIR"/output/image/*.img 2>/dev/null | head -n1 || true)"
	fi
	if [[ -z "$img" ]]; then
		log "ОШИБКА: сборка не произвела .img"
		return 1
	fi
	cp "$img" "$OUT"
	log "Готово: $OUT ($(basename "$img"), $(du -h "$OUT" | cut -f1))"
	return 0
}

# -------------------------------------------------------------- симуляция -----
# Собирает настоящий ext4-образ с rootfs-метаданными и списком пакетов.
sim_build() {
	log "Режим: СИМУЛЯЦИЯ (прототип). Для реальной кросс-сборки: LUCKFOX_REAL_BUILD=1"

	# Необязательная задержка — имитирует длительность сборки (для демонстрации очереди).
	if [[ "${LUCKFOX_SIM_DELAY:-0}" -gt 0 ]]; then
		log "Имитация работы: ${LUCKFOX_SIM_DELAY}с"
		sleep "${LUCKFOX_SIM_DELAY}"
	fi

	local workdir
	workdir="$(mktemp -d)"
	trap 'rm -rf "$workdir"' RETURN

	local rootfs="$workdir/rootfs"
	mkdir -p "$rootfs/etc" "$rootfs/usr/lib" "$rootfs/usr/bin" "$rootfs/var/log"

	# JSON-массив компонентов средствами bash (без awk — его может не быть в slim-образе).
	local comp_json="" first=1 IFS_OLD="$IFS"
	IFS=','
	for c in $COMPONENTS; do
		c="${c#"${c%%[![:space:]]*}"}"   # trim leading
		c="${c%"${c##*[![:space:]]}"}"   # trim trailing
		[ -z "$c" ] && continue
		if [ $first -eq 1 ]; then comp_json="\"$c\""; first=0; else comp_json="$comp_json,\"$c\""; fi
	done
	IFS="$IFS_OLD"

	# Метаданные образа.
	cat >"$rootfs/etc/luckfox-image.json" <<EOF
{
  "imageId": "${IMAGE_ID}",
  "name": "${IMAGE_NAME}",
  "distro": "${DISTRO}",
  "board": "${BOARD}",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "components": [${comp_json}]
}
EOF
	echo "Luckfox Pico custom image: ${IMAGE_NAME} (${DISTRO}/${BOARD})" >"$rootfs/etc/issue"
	echo "$COMPONENTS" | tr ',' '\n' | sed '/^[[:space:]]*$/d' >"$rootfs/etc/installed-packages.txt"

	# Размер образа: база 16 МБ + по 1 МБ на компонент (демонстрация).
	local ncomp size_mb
	ncomp="$(grep -c . "$rootfs/etc/installed-packages.txt" 2>/dev/null || echo 0)"
	size_mb=$(( 16 + ncomp ))

	if command -v mkfs.ext4 >/dev/null 2>&1 && command -v dd >/dev/null 2>&1; then
		dd if=/dev/zero of="$OUT" bs=1M count="$size_mb" status=none
		# -d заполняет ФС содержимым каталога (util-linux/e2fsprogs >= 1.43).
		if mkfs.ext4 -q -F -L luckfox -d "$rootfs" "$OUT" 2>/dev/null; then
			log "Создан ext4-образ с rootfs (${size_mb} МБ)"
		else
			mkfs.ext4 -q -F -L luckfox "$OUT"
			log "Создан ext4-образ (${size_mb} МБ, без -d)"
		fi
	else
		# Фолбэк без e2fsprogs: упакованный tar как .img.
		log "mkfs.ext4 недоступен — собираю tar-артефакт"
		tar -C "$rootfs" -cf "$OUT" .
	fi

	log "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
	return 0
}

# ------------------------------------------------------------------ запуск -----
if [[ "${LUCKFOX_REAL_BUILD:-0}" == "1" ]]; then
	real_build
else
	sim_build
fi
