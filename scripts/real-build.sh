#!/usr/bin/env bash
#
# real-build.sh — РЕАЛЬНАЯ сборка прошиваемого образа Luckfox через SDK,
# с выводом прямо в консоль (видно весь процесс).
#
# Учитывает особенности окружения (WSL):
#   * чистит $PATH от Windows-путей с пробелами (иначе buildroot падает);
#   * ставит таймауты wget (мёртвые зеркала не вешают сборку);
#   * выбирает плату (по умолчанию RV1103_Luckfox_Pico, SD_CARD/Buildroot).
#
# Использование:
#   ./scripts/real-build.sh                      # плата по умолчанию, полная сборка
#   ./scripts/real-build.sh RV1106_Luckfox_Pico_Pro_Max
#   ./scripts/real-build.sh RV1103_Luckfox_Pico rootfs   # только этап (uboot|kernel|rootfs)
#
# ВАЖНО: запускайте в обычном терминале (не в фоне с урезанной сетью).
# Первый прогон долгий (buildroot качает и компилирует пакеты, ~1–3 ч).

set -euo pipefail

BOARD="${1:-RV1103_Luckfox_Pico}"
STAGE="${2:-}"   # пусто = полная сборка (build_allsave)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${LUCKFOX_SDK_DIR:-$REPO_ROOT/luckfox-pico}"

# 1) Чистим PATH от элементов с пробелами/табами (Windows-пути из WSL).
PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vE '[[:space:]]' | paste -sd:)"
export PATH

# 2) Таймауты wget, чтобы мёртвые зеркала отваливались быстро на fallback.
if [ ! -f "$HOME/.wgetrc" ] || ! grep -q '^timeout' "$HOME/.wgetrc" 2>/dev/null; then
	cat > "$HOME/.wgetrc" <<'RC'
timeout = 45
tries = 2
dns_timeout = 15
connect_timeout = 15
read_timeout = 45
RC
fi

cd "$SDK_DIR"

# 3) Выбор платы (SD_CARD/Buildroot предпочтительно).
cfg="$(ls project/cfg/BoardConfig_IPC/BoardConfig-SD_CARD-Buildroot-*"$BOARD"*.mk 2>/dev/null | head -n1 || true)"
[ -z "$cfg" ] && cfg="$(ls project/cfg/BoardConfig_IPC/BoardConfig-*"$BOARD"*.mk 2>/dev/null | head -n1 || true)"
if [ -z "$cfg" ]; then
	echo "Не найден BoardConfig для '$BOARD'. Доступные платы:"
	ls project/cfg/BoardConfig_IPC/BoardConfig-*.mk | sed 's#.*/BoardConfig-##;s#\.mk$##'
	exit 1
fi
echo "==> Плата: $BOARD"
echo "==> BoardConfig: $cfg"
ln -rfs "$cfg" "$SDK_DIR/.BoardConfig.mk"

# 4) Сборка с выводом в консоль.
if [ -n "$STAGE" ]; then
	echo "==> Этап: $STAGE"
	exec ./build.sh "$STAGE"
else
	echo "==> Полная сборка + прошиваемый update.img"
	./build.sh
	./build.sh updateimg
	echo ""
	echo "==> Готовые образы:"
	ls -la "$SDK_DIR/output/image/" 2>/dev/null || true
fi
