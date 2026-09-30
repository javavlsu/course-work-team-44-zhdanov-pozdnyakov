"""Сборка Excel-отчёта по интеграционному и нагрузочному тестированию.

  python3 build_report.py <results_dir> <out.xlsx>

results_dir: integration_results.json, e2e_results.json, load_results.json, heavy_queries.json,
             env.json, conclusions.json (выводы и дефекты по нагрузке, пишутся вручную после анализа)
"""
import json
import os
import sys

from openpyxl import Workbook
from openpyxl.chart import BarChart, LineChart, Reference
from openpyxl.formatting.rule import CellIsRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

D = sys.argv[1]
OUT = sys.argv[2]


def jload(name, default=None):
    p = os.path.join(D, name)
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else default


INTEG = jload("integration_results.json", {"results": [], "defects": []})
E2E = jload("e2e_results.json", {"results": []})
LOAD = jload("load_results.json", {})
HEAVY = jload("heavy_queries.json", {})
ENV = jload("env.json", {})
CONC = jload("conclusions.json", {})
# Повторные прогоны после оптимизации (лист «Оптимизация»)
LOAD_AFTER = jload("load_results_after.json", {})
HEAVY_AFTER = jload("heavy_queries_after.json", {})
INTEG_AFTER = jload("integration_results_after.json", {})

FONT = "Arial"
F = Font(name=FONT, size=10)
FB = Font(name=FONT, size=10, bold=True)
FH = Font(name=FONT, size=10, bold=True, color="FFFFFF")
FT = Font(name=FONT, size=14, bold=True, color="1F3864")
FS = Font(name=FONT, size=11, bold=True, color="1F3864")
FLINK = Font(name=FONT, size=10, color="008000")
HEAD = PatternFill("solid", fgColor="1F4E78")
SUB = PatternFill("solid", fgColor="DDEBF7")
THIN = Side(style="thin", color="A6A6A6")
BOX = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)
WRAP = Alignment(wrap_text=True, vertical="top")
CENTER = Alignment(horizontal="center", vertical="top", wrap_text=True)
GREEN = PatternFill("solid", fgColor="C6EFCE")
RED = PatternFill("solid", fgColor="FFC7CE")
GRAY = PatternFill("solid", fgColor="EDEDED")
SEV_FILL = {"Критическая": "C00000", "Высокая": "FF6B6B", "Средняя": "F4B183", "Низкая": "FFE699"}
SEV_ORDER = {"Критическая": 0, "Высокая": 1, "Средняя": 2, "Низкая": 3}

wb = Workbook()
wb.calculation.fullCalcOnLoad = True


def unsmooth(ch):
    """Без сглаживания: сглаженные кривые рисуют несуществующие провалы между точками."""
    for s_ in ch.series:
        s_.smooth = False
    return ch


def sheet(title, first=False):
    ws = wb.active if first else wb.create_sheet()
    ws.title = title
    ws.sheet_view.showGridLines = False
    return ws


def widths(ws, ws_widths):
    for i, w in enumerate(ws_widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w


def header(ws, row, cols, fill=HEAD, font=FH):
    for i, c in enumerate(cols, 1):
        cell = ws.cell(row=row, column=i, value=c)
        cell.font, cell.fill, cell.border, cell.alignment = font, fill, BOX, CENTER
    ws.row_dimensions[row].height = 30


def put(ws, row, values, fmt=None, bold=False, align=None):
    for i, v in enumerate(values, 1):
        c = ws.cell(row=row, column=i, value=v)
        c.font = FB if bold else F
        c.border = BOX
        c.alignment = align or WRAP
        if fmt and i in fmt:
            c.number_format = fmt[i]
    return row + 1


def title(ws, text, sub=None):
    ws["A1"] = text
    ws["A1"].font = FT
    if sub:
        ws["A2"] = sub
        ws["A2"].font = Font(name=FONT, size=10, italic=True, color="595959")


def section(ws, row, text):
    ws.cell(row=row, column=1, value=text).font = FS
    return row + 1


def status_rules(ws, rng):
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"PASS"'], fill=GREEN))
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"FAIL"'], fill=RED))
    ws.conditional_formatting.add(rng, CellIsRule(operator="equal", formula=['"SKIP"'], fill=GRAY))


def na(v):
    return "—" if v is None else v


# ------------------------------------------------------------ описания профилей ---
PROFILES = [
    ("smoke", "L-01", "Дымовой (baseline)",
     "Проверить работоспособность всех пользовательских путей под минимальной нагрузкой и получить эталонные времена отклика.",
     "constant-vus: 1 VU, 1 мин, пауза 1 с между итерациями",
     "Открытие сайта + ассеты → библиотека образов и компонентов → типы → вход → профиль → «Мои образы» → список пользователей (ADMIN); каждая 5-я итерация — заказ сборки, ожидание READY и скачивание",
     "ошибки < 1 %; p95 < 300 мс"),
    ("browse", "L-02", "Просмотр каталогов (гости)",
     "Типовая нагрузка анонимных посетителей: чтение каталогов с поиском, фильтрами, сортировкой и пагинацией.",
     "ramping-vus: 0→50 VU за 1 мин, 3 мин удержание, 30 с спад; «время на чтение» 1–3 с",
     "Библиотека образов (случайные search/state/sort/page, 30 % — фильтр по 2 компонентам), 40 % — карточка образа, 50 % — библиотека компонентов, 20 % — типы",
     "ошибки < 1 %; p95 чтения < 500 мс"),
    ("mixed", "L-03", "Смешанный продакшен-профиль (целевая нагрузка)",
     "Ожидаемая пиковая нагрузка: 100 одновременных пользователей разных ролей работают параллельно.",
     "4 параллельных сценария, ramping-vus 1 мин разгон, 5 мин удержание: гости 70 VU, пользователи 20 VU, заказчики сборок 8 VU, администраторы 2 VU",
     "Гости — как L-02; пользователи — вход, профиль, «Мои образы», каталоги; заказчики — каталог → заказ сборки → опрос статуса каждые 2 с → скачивание 18–19 МБ; админы — список пользователей, создание/изменение/удаление компонента",
     "ошибки < 1 %; p95 чтения < 500 мс; p95 записи < 1000 мс"),
    ("stress", "L-04", "Стресс (поиск предела)",
     "Найти точку насыщения: при какой интенсивности растёт задержка и появляются ошибки.",
     "ramping-arrival-rate без пауз: ступени 50 → 100 → 200 → 300 → 450 → 600 итераций/с по 1 мин (30 с разгон между ступенями), до 1500 VU",
     "Только чтение: 55 % библиотека образов (с фильтрами), 35 % компоненты, 5 % типы, 5 % HTML-страница; одна итерация ≈ 1,3 HTTP-запроса (часть итераций дополнительно открывает карточку)",
     "ошибки < 1 %; p95 < 500 мс (ожидается нарушение на верхних ступенях)"),
    ("breakpoint", "L-04b", "Стресс до отказа (breakpoint)",
     "Продолжение L-04: найти предел пропускной способности чтения, при котором растут задержки/ошибки.",
     "ramping-arrival-rate без пауз: ступени 1000 → 1500 → 2000 → 3000 → 4000 итераций/с по 1 мин, до 3000 VU",
     "Как в L-04 (одна итерация ≈ 1,3 HTTP-запроса)",
     "фиксация точки насыщения (ошибки ≥ 1 % или p95 ≥ 500 мс)"),
    ("spike", "L-05", "Всплеск (spike)",
     "Проверить реакцию на резкий наплыв (например, ссылка в соцсети) и восстановление после него.",
     "ramping-vus: 10 VU 1 мин → 400 VU за 10 с → 1 мин удержание → 10 VU за 10 с → 1 мин",
     "Гости с короткими паузами 0,5–1,5 с: образы, компоненты, карточки",
     "ошибки < 1 %; p95 < 500 мс; после всплеска задержка возвращается к исходной"),
    ("login", "L-06", "Шторм авторизаций",
     "Оценить пропускную способность входа (BCrypt — CPU-ёмкая операция) — например, утренний наплыв.",
     "ramping-arrival-rate: 10 → 25 → 50 → 100 входов/с по 30 с",
     "POST /auth/login существующими пользователями (50 учётных записей)",
     "ошибки < 1 %; p95 входа < 1000 мс"),
    ("burst", "L-07", "Пакет заказов сборки (очередь)",
     "60 пользователей одновременно заказывают сборку: проверка очереди с одним воркером, времени ожидания и отсутствия потерь.",
     "per-vu-iterations: 60 VU × 1 заказ, опрос статуса каждые 2 с до READY (макс. 20 мин)",
     "POST /images/build → опрос GET /images/{id}",
     "ошибки < 1 %; p95 заказа < 1000 мс; все 60 образов READY"),
    ("download", "L-08", "Скачивание артефактов",
     "Нагрузка на отдачу больших файлов (.img 17–19 МБ): пропускная способность и влияние на API.",
     "constant-vus: 20 VU, 2 мин, пауза 0,5–1,5 с",
     "GET /images/{id}/download случайного готового образа",
     "ошибки < 1 %; p95 < 3000 мс"),
    ("soak", "L-09", "Длительная нагрузка (soak)",
     "Выявить деградацию во времени: утечки памяти, рост задержек, исчерпание соединений.",
     "constant-vus 15 мин: гости 28, пользователи 8, заказчики 3, админ 1 (40 VU)",
     "Как в L-03",
     "ошибки < 1 %; p95 чтения < 500 мс; нет роста heap/RSS и задержки к концу"),
    ("volume_base", "L-10a", "Объём: базовая БД",
     "Эталон для объёмного теста — чтение каталогов на стартовых данных при фиксированной интенсивности.",
     "constant-arrival-rate: 40 запросов/с, 3 мин",
     "Как в L-04 (только чтение)",
     "ошибки < 1 %; p95 < 500 мс"),
    ("volume", "L-10b", "Объём: БД ×100",
     "То же при объёме данных, близком к продакшену через 1–2 года (20 000 образов, 2 000 компонентов, 5 000 пользователей).",
     "constant-arrival-rate: 40 запросов/с, 3 мин",
     "Как в L-04 (только чтение)",
     "ошибки < 1 %; p95 < 500 мс"),
]
PROFILE_META = {p[0]: p for p in PROFILES}
PRESENT = [p for p in PROFILES if p[0] in LOAD]

# =================================================================== Сводка ===
ws = sheet("Сводка", first=True)
title(ws, "Отчёт: интеграционное и нагрузочное тестирование Luckfox Image Builder",
      f"Дата проведения: {ENV.get('date', '')} · версия: {ENV.get('git', '')} · стенд: {ENV.get('stand_short', '')}")
widths(ws, [44, 14, 14, 14, 16, 16, 16])
r = 4
r = section(ws, r, "1. Интеграционное тестирование (по этапам)")
header(ws, r, ["Этап", "Всего", "PASS", "FAIL", "Успешно, %"])
r += 1
stages = []
for t in INTEG["results"]:
    if t["stage"] not in stages:
        stages.append(t["stage"])
stages.append("8. E2E через браузер (UI)")
first = r
IT = "'Интеграционные тесты'"
for s in stages:
    put(ws, r, [s, f'=COUNTIF({IT}!$C:$C,A{r})', f'=COUNTIFS({IT}!$C:$C,A{r},{IT}!$H:$H,"PASS")',
                f'=COUNTIFS({IT}!$C:$C,A{r},{IT}!$H:$H,"FAIL")', f"=IF(B{r}=0,0,C{r}/B{r})"], fmt={5: "0%"})
    r += 1
put(ws, r, ["Итого", f"=SUM(B{first}:B{r - 1})", f"=SUM(C{first}:C{r - 1})", f"=SUM(D{first}:D{r - 1})",
            f"=IF(B{r}=0,0,C{r}/B{r})"], fmt={5: "0%"}, bold=True)
r += 2

r = section(ws, r, "2. Найденные дефекты по серьёзности")
header(ws, r, ["Серьёзность", "Количество"])
r += 1
first = r
for sev in SEV_ORDER:
    put(ws, r, [sev, f"=COUNTIF('Дефекты'!$B:$B,A{r})"])
    ws.cell(row=r, column=1).fill = PatternFill("solid", fgColor=SEV_FILL[sev])
    if sev == "Критическая":
        ws.cell(row=r, column=1).font = Font(name=FONT, size=10, bold=True, color="FFFFFF")
    r += 1
put(ws, r, ["Всего", f"=SUM(B{first}:B{r - 1})"], bold=True)
r += 2

r = section(ws, r, "3. Нагрузочное тестирование (ключевые показатели)")
header(ws, r, ["Профиль", "Запросов", "RPS средн.", "Ошибки", "p95, мс", "p99, мс", "Критерии (SLA)"])
r += 1
for i, p in enumerate(PRESENT):
    src = 5 + i  # строка профиля на листе «Нагрузка — итоги»
    NI = "'Нагрузка — итоги'"
    put(ws, r, [f"{p[1]} {p[2]}", f"={NI}!D{src}", f"={NI}!F{src}", f"={NI}!I{src}", f"={NI}!N{src}",
                f"={NI}!O{src}", f"={NI}!AB{src}"], fmt={2: "#,##0", 3: "#,##0.0", 4: "0.00%", 5: "#,##0.0", 6: "#,##0.0"})
    for c in range(2, 8):
        ws.cell(row=r, column=c).font = FLINK
    r += 1
status_rules(ws, f"G{r - len(PRESENT)}:G{r}")
r += 1

r = section(ws, r, "4. Выводы")
for line in CONC.get("summary", []):
    ws.cell(row=r, column=1, value="• " + line).font = F
    ws.cell(row=r, column=1).alignment = WRAP
    ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=7)
    ws.row_dimensions[r].height = max(15, 15 * (len(line) // 120 + 1))
    r += 1

# ========================================================= Стенд и методика ===
ws = sheet("Стенд и методика")
title(ws, "Тестовый стенд и методика")
widths(ws, [34, 110])
r = 3
for block, rows in ENV.get("blocks", []):
    r = section(ws, r, block)
    for k, v in rows:
        r = put(ws, r, [k, v])
    r += 1
r = section(ws, r, "Методика")
for k, v in CONC.get("methodology", []):
    r = put(ws, r, [k, v])

# ===================================================== Интеграционные тесты ===
ws = sheet("Интеграционные тесты")
title(ws, "Интеграционные тесты (чёрный ящик по HTTP на прод-подобном стенде + E2E в браузере)",
      f"Прогон {INTEG.get('run_id', '')}, завершён {INTEG.get('finished', '')}. "
      "Скрипт: tests/integration/run_integration.py; E2E: Playwright.")
widths(ws, [5, 9, 24, 34, 42, 38, 52, 9, 9, 10])
header(ws, 4, ["№", "ID", "Этап", "Тест", "Описание / шаги", "Ожидаемый результат",
               "Фактический результат", "Статус", "Время, с", "Дефект"])
ws.freeze_panes = "C5"
r = 5
rows = list(INTEG["results"]) + [dict(stage="8. E2E через браузер (UI)", defect_id=t.get("defect_id", ""), **{
    k: v for k, v in t.items() if k != "defect_id"}) for t in E2E["results"]]
for n, t in enumerate(rows, 1):
    r = put(ws, r, [n, t["id"], t["stage"], t["name"], t["description"], t["expected"], t["actual"],
                    t["status"], t.get("duration_s"), t.get("defect_id", "")], fmt={9: "0.00"})
    ws.cell(row=r - 1, column=8).alignment = CENTER
status_rules(ws, f"H5:H{r}")
ws.auto_filter.ref = f"A4:J{r - 1}"

# ================================================================= Дефекты ===
ws = sheet("Дефекты")
title(ws, "Реестр дефектов", "Серьёзность: Критическая — безопасность/потеря данных; Высокая — функция недоступна; "
      "Средняя — неверное поведение с обходом; Низкая — косметика/удобство.")
widths(ws, [9, 13, 60, 22, 60, 24])
header(ws, 4, ["ID", "Серьёзность", "Описание", "Где обнаружен", "Рекомендация по исправлению", "Статус"])
ws.freeze_panes = "A5"
defects = list(INTEG.get("defects", []))
e2e_links = {}
for t in E2E["results"]:
    if t.get("defect_id"):
        e2e_links.setdefault(t["defect_id"], []).append(t["id"])
for d in defects:
    d["tests"] = list(d.get("tests", [])) + e2e_links.get(d["id"], [])
for d in CONC.get("override_defects", []):
    for x in defects:
        if x["id"] == d["id"]:
            x.update(d)
defects += CONC.get("extra_defects", [])
defects.sort(key=lambda x: (SEV_ORDER.get(x["severity"], 9), x["id"]))
r = 5
for d in defects:
    fixed = CONC.get("optimization", {}).get("fixed", {}).get(d["id"])
    r = put(ws, r, [d["id"], d["severity"], d["title"], ", ".join(d["tests"]), d["recommendation"], fixed or "Открыт"])
    if fixed:
        ws.cell(row=r - 1, column=6).fill = GREEN
    c = ws.cell(row=r - 1, column=2)
    c.fill = PatternFill("solid", fgColor=SEV_FILL.get(d["severity"], "FFFFFF"))
    c.alignment = CENTER
    if d["severity"] == "Критическая":
        c.font = Font(name=FONT, size=10, bold=True, color="FFFFFF")
ws.auto_filter.ref = f"A4:F{r - 1}"

# ===================================================== Нагрузка — сценарии ===
ws = sheet("Нагрузка — сценарии")
title(ws, "Сценарии нагрузочного тестирования (k6)", "Скрипт: tests/load/scenarios.js, запуск: tests/load/run_load.sh <профиль>")
widths(ws, [8, 26, 44, 44, 60, 36])
header(ws, 4, ["ID", "Профиль", "Цель", "Модель нагрузки", "Действия виртуальных пользователей", "Критерии успеха (SLA)"])
r = 5
for p in PRESENT:
    r = put(ws, r, [p[1], f"{p[2]}\n(PROFILE={p[0]})", p[3], p[4], p[5], p[6]])

# ======================================================= Нагрузка — итоги ===
ws = sheet("Нагрузка — итоги")
title(ws, "Итоги нагрузочных профилей", "Задержки — http_req_duration k6 (мс). Ресурсы — посекундный мониторинг /proc + jstat + "
      "SHOW GLOBAL STATUS. CPU процесса: 100 % = 1 ядро (всего 16 логических).")
cols = ["ID", "Профиль", "Длит., с", "Запросов", "Итераций", "RPS средн.", "RPS пик", "VU макс.", "Ошибки",
        "Коды ответа", "Avg, мс", "Min, мс", "Med, мс", "p95, мс", "p99, мс", "Max, мс", "p90, мс",
        "Получено, МБ", "МБ/с", "CPU java ср./макс., %", "CPU mysqld ср./макс., %", "CPU машины ср./макс., %",
        "Heap макс., МБ", "RSS java нач.→кон., МБ", "GC (кол-во / с)", "Соед. БД макс.", "SQL/с", "SLA", "Комментарий"]
widths(ws, [7, 30, 9, 11, 10, 10, 9, 9, 9, 22, 9, 9, 9, 9, 9, 10, 9, 11, 8, 14, 14, 14, 11, 14, 12, 10, 9, 9, 60])
header(ws, 4, cols)
ws.freeze_panes = "C5"
r = 5
for p in PRESENT:
    L = LOAD[p[0]]
    o, res = L["overall"], L["resources"]
    sla = "PASS" if L["thresholds"] and all(t["ok"] for t in L["thresholds"]) else "FAIL"

    def mx(k):
        v = res.get(k) or {}
        return f"{v.get('avg', '—')} / {v.get('max', '—')}"
    put(ws, r, [p[1], p[2], o["duration_s"], o["requests"], o.get("iterations"), o["rps_avg"], o["rps_peak"],
                o.get("vus_max"), o["error_pct"] / 100,
                ", ".join(f"{k}: {v}" for k, v in o["status"].items())
                + (f"; пропущено итераций: {o['dropped_iterations']}" if o.get("dropped_iterations") else ""),
                o.get("lat_avg"), o.get("lat_min"), o.get("lat_med"), o.get("lat_p95"), o.get("lat_p99"),
                o.get("lat_max"), o.get("lat_p90"), o["data_received_mb"], o["throughput_mbps"],
                mx("java_cpu"), mx("mysqld_cpu"), mx("cpu_total"), (res.get("heap") or {}).get("max"),
                f"{na(res.get('java_rss_start'))} → {na(res.get('java_rss_end'))}",
                f"{na(res.get('gc_count'))} / {na(res.get('gc_time_s'))}", (res.get("db_conn") or {}).get("max"),
                res.get("sql_per_sec"), sla, CONC.get("profile_comments", {}).get(p[0], "")],
        fmt={4: "#,##0", 6: "#,##0.0", 9: "0.00%", 11: "#,##0.0", 12: "#,##0.0", 13: "#,##0.0", 14: "#,##0.0",
             15: "#,##0.0", 16: "#,##0.0", 17: "#,##0.0"})
    r += 1
status_rules(ws, f"AB5:AB{r}")
r += 1
r = section(ws, r, "Проверка критериев (thresholds k6)")
header(ws, r, ["ID", "Профиль", "Метрика", "Условие", "Результат"])
r += 1
t_first = r
for p in PRESENT:
    for t in LOAD[p[0]]["thresholds"]:
        r = put(ws, r, [p[1], p[2], t["metric"], t["expr"], "PASS" if t["ok"] else "FAIL"])
status_rules(ws, f"E{t_first}:E{r}")
r += 1
r = section(ws, r, "Проверки ответов (checks k6): только с неуспешными")
header(ws, r, ["ID", "Профиль", "Проверка", "Успешно", "Неуспешно", "Доля неуспешных"])
r += 1
for p in PRESENT:
    for c in LOAD[p[0]]["checks"]:
        if c["fails"]:
            put(ws, r, [p[1], p[2], c["name"], c["passes"], c["fails"], f"=E{r}/(D{r}+E{r})"], fmt={6: "0.00%"})
            r += 1

# =================================================== Нагрузка — эндпоинты ===
ws = sheet("Нагрузка — эндпоинты")
title(ws, "Время отклика по операциям (тег name в k6), мс")
widths(ws, [7, 30, 20, 11, 9, 9, 9, 9, 9, 9, 9, 10, 9])
header(ws, 3, ["ID", "Профиль", "Операция", "Запросов", "Ошибок", "Avg", "Min", "Med", "p90", "p95", "p99", "Max", "% ошибок"])
ws.freeze_panes = "D4"
r = 4
for p in PRESENT:
    for e in LOAD[p[0]]["endpoints"]:
        put(ws, r, [p[1], p[2], e["name"], e["count"], e["errors"], e["avg"], e["min"], e["med"], e["p90"],
                    e["p95"], e["p99"], e["max"], f"=IF(D{r}=0,0,E{r}/D{r})"],
            fmt={4: "#,##0", 6: "#,##0.0", 7: "#,##0.0", 8: "#,##0.0", 9: "#,##0.0", 10: "#,##0.0",
                 11: "#,##0.0", 12: "#,##0.0", 13: "0.00%"})
        r += 1
ws.auto_filter.ref = f"A3:M{r - 1}"

# ========================================================== Ступени нагрузки ===
ws = sheet("Ступени нагрузки")
title(ws, "Поведение системы на ступенях нагрузки (плато ступени, без участков разгона)")
widths(ws, [26, 16, 14, 12, 12, 12, 12, 14])
r = 3
for prof, label, unit in (("stress", "L-04 Стресс: чтение каталогов", "Цель, итераций/с"),
                          ("breakpoint", "L-04b Стресс до отказа", "Цель, итераций/с"),
                          ("login", "L-06 Шторм авторизаций", "Цель, входов/с"),
                          ("spike", "L-05 Всплеск", "Фаза")):
    if prof not in LOAD or not LOAD[prof]["steps"]:
        continue
    r = section(ws, r, label)
    header(ws, r, [unit, "Достигнуто, /с", "Med, мс", "p95, мс", "p99, мс", "Ошибки", "CPU java, %"])
    top = r
    r += 1
    for s in LOAD[prof]["steps"]:
        r = put(ws, r, [s["target"], s["achieved_rps"], s["med"], s["p95"], s["p99"],
                        (s["err_pct"] or 0) / 100, s["java_cpu"]],
                fmt={2: "#,##0.0", 3: "#,##0.0", 4: "#,##0.0", 5: "#,##0.0", 6: "0.00%"})
    ch = LineChart()
    ch.title = f"{label}: задержка на ступенях"
    ch.y_axis.title = "мс"
    ch.x_axis.title = unit
    ch.add_data(Reference(ws, min_col=3, max_col=5, min_row=top, max_row=r - 1), titles_from_data=True)
    ch.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=r - 1))
    ch.height, ch.width = 7.5, 18
    ws.add_chart(unsmooth(ch), f"J{top}")
    bc = BarChart()
    bc.title = f"{label}: пропускная способность"
    bc.y_axis.title = "/с"
    bc.add_data(Reference(ws, min_col=2, min_row=top, max_row=r - 1), titles_from_data=True)
    bc.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=r - 1))
    bc.height, bc.width = 7.5, 12
    ws.add_chart(bc, f"U{top}")
    r = max(r + 2, top + 17)

# ========================================================== Временные ряды ===
ws = sheet("Временные ряды")
title(ws, "Временные ряды (шаг 5 с): нагрузка, отклик, ресурсы", "Графики — справа от каждого блока")
widths(ws, [8, 8, 9, 10, 10, 9, 11, 11, 11, 11, 11])
r = 3
tl_cols = ["t, с", "VU", "RPS", "p95, мс", "Avg, мс", "Ошибки, %", "CPU java, %", "CPU mysqld, %",
           "CPU машины, %", "Heap, МБ", "Соед. БД"]
for p in PRESENT:
    tl = LOAD[p[0]]["timeline"]
    if len(tl) < 3:
        continue
    r = section(ws, r, f"{p[1]} {p[2]}")
    header(ws, r, tl_cols)
    top = r
    r += 1
    for x in tl:
        r = put(ws, r, [x["t"], x["vus"], x["rps"], x["p95"], x["avg"], x["err_pct"], x["java_cpu"], x["mysqld_cpu"],
                        x["cpu_total"], x["heap"], x["db_conn"]])
    end = r - 1

    def line(cols_, ttl, ytitle, anchor):
        ch = LineChart()
        ch.title, ch.y_axis.title, ch.x_axis.title = ttl, ytitle, "секунды"
        for c in cols_:
            ch.add_data(Reference(ws, min_col=c, min_row=top, max_row=end), titles_from_data=True)
        ch.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=end))
        ch.height, ch.width = 7, 16
        ch.x_axis.tickLblSkip = max(1, len(tl) // 12)
        ws.add_chart(unsmooth(ch), anchor)
    line([2, 3], f"{p[1]}: VU и RPS", "", f"M{top}")
    line([4, 5], f"{p[1]}: время отклика", "мс", f"W{top}")
    line([7, 8, 9], f"{p[1]}: CPU", "%", f"AG{top}")
    r = max(r + 2, top + 16)

# =========================================================== Объёмный тест ===
ws = sheet("Объёмный тест")
title(ws, "Объёмный тест: запросы каталога на стартовой БД и на БД ×100",
      "Одиночные запросы без конкурентной нагрузки, 30 повторов (tests/load/heavy_queries.py); "
      "SQL — число запросов к MySQL на один HTTP-запрос.")
widths(ws, [40, 11, 11, 9, 9, 11, 11, 11, 11, 11])
r = 4
if "base" in HEAVY and "volume" in HEAVY:
    header(ws, r, ["Запрос", "Записей (база)", "Записей (×100)", "SQL (база)", "SQL (×100)",
                   "Med база, мс", "Med ×100, мс", "p95 база, мс", "p95 ×100, мс", "Рост p95, раз"])
    r += 1
    for a, b in zip(HEAVY["base"], HEAVY["volume"]):
        put(ws, r, [a["name"], a["total"], b["total"], a["sql"], b["sql"], a["med"], b["med"], a["p95"], b["p95"],
                    f"=IF(H{r}=0,0,I{r}/H{r})"], fmt={2: "#,##0", 3: "#,##0", 6: "0.0", 7: "0.0", 8: "0.0",
                                                     9: "0.0", 10: "0.0x"})
        r += 1
    ch = BarChart()
    ch.type = "bar"
    ch.title = "p95, мс: стартовая БД и БД ×100"
    ch.add_data(Reference(ws, min_col=8, max_col=9, min_row=4, max_row=r - 1), titles_from_data=True)
    ch.set_categories(Reference(ws, min_col=1, min_row=5, max_row=r - 1))
    ch.height, ch.width = 9, 18
    ws.add_chart(ch, f"L4")
    r += 2
if "volume_base" in LOAD and "volume" in LOAD:
    r = section(ws, r, "Под нагрузкой 40 запросов/с, 3 мин (профили L-10a и L-10b)")
    header(ws, r, ["Показатель", "Стартовая БД", "БД ×100", "Изменение"])
    r += 1
    a, b = LOAD["volume_base"], LOAD["volume"]
    for label, fa in (("Средняя задержка, мс", lambda x: x["overall"]["lat_avg"]),
                      ("Медиана, мс", lambda x: x["overall"]["lat_med"]),
                      ("p95, мс", lambda x: x["overall"]["lat_p95"]),
                      ("p99, мс", lambda x: x["overall"]["lat_p99"]),
                      ("Ошибки, %", lambda x: x["overall"]["error_pct"]),
                      ("CPU java, средн. %", lambda x: (x["resources"]["java_cpu"] or {}).get("avg")),
                      ("CPU mysqld, средн. %", lambda x: (x["resources"]["mysqld_cpu"] or {}).get("avg")),
                      ("SQL-запросов/с", lambda x: x["resources"]["sql_per_sec"])):
        put(ws, r, [label, fa(a), fa(b), f'=IF(OR(B{r}="",B{r}=0),"—",C{r}/B{r})'], fmt={4: "0.0x"})
        r += 1
    r += 1
for line_ in CONC.get("volume_notes", []):
    ws.cell(row=r, column=1, value="• " + line_).font = F
    ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=10)
    ws.cell(row=r, column=1).alignment = WRAP
    ws.row_dimensions[r].height = 30
    r += 1

# =========================================================== Очередь сборок ===
ws = sheet("Очередь сборок")
title(ws, "Очередь сборок под нагрузкой", "Сборка в режиме симуляции с задержкой LUCKFOX_SIM_DELAY=3 с (+ mkfs.ext4); "
      "время до READY измеряется от ответа на заказ до статуса READY.")
widths(ws, [30, 14, 14, 14, 14, 14, 14, 14, 14, 14])
header(ws, 4, ["Профиль", "Готово", "Сбоев", "Avg, с", "Min, с", "Med, с", "p90, с", "p95, с", "Max, с",
               "Пропускн., сборок/мин"])
r = 5
for key in ("smoke", "mixed", "burst", "soak"):
    if key not in LOAD or not LOAD[key].get("builds"):
        continue
    b = LOAD[key]["builds"]
    t = b["time_to_ready_s"]
    if not t.get("count"):
        continue
    dur = LOAD[key]["overall"]["duration_s"] or 1
    put(ws, r, [f"{PROFILE_META[key][1]} {PROFILE_META[key][2]}", b["ready"], b["failed"], t["avg"], t["min"],
                t["med"], t["p90"], t["p95"], t["max"], round(b["ready"] / (dur / 60), 1)],
        fmt={4: "0.0", 5: "0.0", 6: "0.0", 7: "0.0", 8: "0.0", 9: "0.0"})
    r += 1
r += 1
for line_ in CONC.get("queue_notes", []):
    ws.cell(row=r, column=1, value="• " + line_).font = F
    ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=10)
    ws.cell(row=r, column=1).alignment = WRAP
    ws.row_dimensions[r].height = 30
    r += 1

# ============================================================== Оптимизация ===
OPT = CONC.get("optimization", {})
if LOAD_AFTER:
    ws = sheet("Оптимизация")
    title(ws, "Оптимизация по итогам нагрузочного тестирования: до и после",
          OPT.get("subtitle", "Те же профили k6, стенд и объём данных; «до» — исходная версия, «после» — с исправлениями."))
    widths(ws, [44, 26, 13, 13, 13, 13, 13, 13])
    r = 4
    r = section(ws, r, "1. Внесённые исправления")
    header(ws, r, ["Изменение", "Дефект", "Файлы"])
    ws.merge_cells(start_row=r, start_column=3, end_row=r, end_column=8)
    r += 1
    for change, defect, files in OPT.get("changes", []):
        put(ws, r, [change, defect, files])
        ws.merge_cells(start_row=r, start_column=3, end_row=r, end_column=8)
        ws.row_dimensions[r].height = 15 * max(2, len(change) // 45 + 1)
        r += 1
    r += 1

    def lower_better(row, before_col="C", after_col="D"):
        return (f'=IF(OR({before_col}{row}="",{after_col}{row}=""),"—",'
                f'IF({after_col}{row}<{before_col}{row},"лучше",IF({after_col}{row}={before_col}{row},"без изменений","хуже")))')

    for key, label in (("base", "стартовая БД (388 образов)"), ("volume", "БД ×100 (20 тыс. образов)")):
        if key not in HEAVY or key not in HEAVY_AFTER:
            continue
        r = section(ws, r, f"2{'а' if key == 'base' else 'б'}. Одиночные запросы каталога, {label}: SQL на HTTP-запрос и задержка")
        header(ws, r, ["Запрос", "", "SQL до", "SQL после", "p95 до, мс", "p95 после, мс", "Ускорение p95, раз", "Итог"])
        top = r
        r += 1
        for a, b in zip(HEAVY[key], HEAVY_AFTER[key]):
            put(ws, r, [a["name"], "", a["sql"], b["sql"], a["p95"], b["p95"],
                        f"=IF(F{r}=0,0,E{r}/F{r})", lower_better(r, "E", "F")],
                fmt={5: "0.0", 6: "0.0", 7: "0.0x"})
            r += 1
        status_rules(ws, f"H{top + 1}:H{r - 1}")
        ch = BarChart()
        ch.type = "bar"
        ch.title = f"SQL-запросов на HTTP-запрос, {label}"
        ch.add_data(Reference(ws, min_col=3, max_col=4, min_row=top, max_row=r - 1), titles_from_data=True)
        ch.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=r - 1))
        ch.height, ch.width = 8, 16
        ws.add_chart(ch, f"J{top}")
        r = max(r + 1, top + 17)

    r = section(ws, r, "3. Под нагрузкой: ключевые показатели профилей до и после")
    header(ws, r, ["Профиль", "Показатель", "До", "После", "Изменение", "Итог"])
    top = r
    r += 1

    def sql_per_req(x):
        s, rps = x["resources"].get("sql_per_sec"), x["overall"]["rps_avg"]
        return round(s / rps, 1) if s and rps else None

    def best_step(x):
        return max((s["achieved_rps"] for s in x["steps"]), default=None)

    def step_val(x, target, k):
        return next((s[k] for s in x["steps"] if s["target"] == target), None)
    metrics = [
        ("breakpoint", "Макс. пропускная способность, запросов/с", best_step, False),
        ("breakpoint", "Медиана на ступени 1 500 ит/с, мс", lambda x: step_val(x, 1500, "med"), True),
        ("breakpoint", "Медиана на ступени 2 000 ит/с, мс", lambda x: step_val(x, 2000, "med"), True),
        ("breakpoint", "Медиана на ступени 4 000 ит/с, мс", lambda x: step_val(x, 4000, "med"), True),
        ("breakpoint", "Ошибки, %", lambda x: x["overall"]["error_pct"], True),
        ("breakpoint", "SQL-запросов на HTTP-запрос", sql_per_req, True),
        ("stress", "p95 за профиль, мс", lambda x: x["overall"]["lat_p95"], True),
        ("stress", "p95 на ступени 600 ит/с, мс", lambda x: step_val(x, 600, "p95"), True),
        ("stress", "CPU mysqld средн., %", lambda x: (x["resources"]["mysqld_cpu"] or {}).get("avg"), True),
        ("stress", "CPU java средн., %", lambda x: (x["resources"]["java_cpu"] or {}).get("avg"), True),
        ("stress", "SQL-запросов на HTTP-запрос", sql_per_req, True),
        ("volume_base", "p95, мс (стартовая БД, 40 запросов/с)", lambda x: x["overall"]["lat_p95"], True),
        ("volume", "p95, мс (БД ×100, 40 запросов/с)", lambda x: x["overall"]["lat_p95"], True),
        ("volume", "p99, мс (БД ×100)", lambda x: x["overall"]["lat_p99"], True),
        ("volume", "CPU mysqld средн., % (БД ×100)", lambda x: (x["resources"]["mysqld_cpu"] or {}).get("avg"), True),
        ("volume", "SQL-запросов на HTTP-запрос (БД ×100)", sql_per_req, True),
        ("mixed", "p95, мс (100 VU, целевой профиль)", lambda x: x["overall"]["lat_p95"], True),
        ("mixed", "p99, мс", lambda x: x["overall"]["lat_p99"], True),
        ("mixed", "Ошибки, %", lambda x: x["overall"]["error_pct"], True),
        ("login", "p95 входа, мс (до 100 входов/с)", lambda x: x["overall"]["lat_p95"], True),
        ("login", "Ошибки, %", lambda x: x["overall"]["error_pct"], True),
    ]
    for prof, label, fn, lower in metrics:
        if prof not in LOAD or prof not in LOAD_AFTER:
            continue
        a, b = fn(LOAD[prof]), fn(LOAD_AFTER[prof])
        verdict = lower_better(r) if lower else (
            f'=IF(OR(C{r}="",D{r}=""),"—",IF(D{r}>C{r},"лучше",IF(D{r}=C{r},"без изменений","хуже")))')
        put(ws, r, [f"{PROFILE_META[prof][1]} {PROFILE_META[prof][2]}", label, a, b,
                    f'=IF(OR(C{r}="",C{r}=0,D{r}=""),"—",(D{r}-C{r})/C{r})', verdict],
            fmt={3: "#,##0.0", 4: "#,##0.0", 5: "+0%;-0%;0%"})
        r += 1
    status_rules(ws, f"F{top + 1}:F{r - 1}")
    r += 1

    for letter, prof, label in (("а", "breakpoint", "L-04b Стресс до отказа"), ("б", "stress", "L-04 Стресс")):
        if prof not in LOAD or prof not in LOAD_AFTER:
            continue
        r = section(ws, r, f"4{letter}. {label}: ступени до и после")
        header(ws, r, ["Цель, итераций/с", "Достигнуто до, /с", "Достигнуто после, /с", "Med до, мс", "Med после, мс",
                       "p95 до, мс", "p95 после, мс", "Ошибки после"])
        top = r
        r += 1
        for sa, sb in zip(LOAD[prof]["steps"], LOAD_AFTER[prof]["steps"]):
            r = put(ws, r, [sa["target"], sa["achieved_rps"], sb["achieved_rps"], sa["med"], sb["med"], sa["p95"],
                            sb["p95"], (sb["err_pct"] or 0) / 100],
                    fmt={2: "#,##0.0", 3: "#,##0.0", 4: "#,##0.0", 5: "#,##0.0", 6: "#,##0.0", 7: "#,##0.0", 8: "0.00%"})
        bc = BarChart()
        bc.title = f"{label}: достигнутая пропускная способность"
        bc.y_axis.title = "запросов/с"
        bc.add_data(Reference(ws, min_col=2, max_col=3, min_row=top, max_row=r - 1), titles_from_data=True)
        bc.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=r - 1))
        bc.height, bc.width = 7.5, 14
        ws.add_chart(bc, f"J{top}")
        ch = LineChart()
        ch.title = f"{label}: медиана отклика"
        ch.y_axis.title, ch.x_axis.title = "мс", "цель, итераций/с"
        ch.add_data(Reference(ws, min_col=4, max_col=5, min_row=top, max_row=r - 1), titles_from_data=True)
        ch.set_categories(Reference(ws, min_col=1, min_row=top + 1, max_row=r - 1))
        ch.height, ch.width = 7.5, 14
        ws.add_chart(unsmooth(ch), f"R{top}")
        r = max(r + 2, top + 17)

    if INTEG_AFTER.get("results"):
        r = section(ws, r, "5. Регрессия: повторный интеграционный прогон на исправленной версии")
        header(ws, r, ["Прогон", "Тестов", "PASS", "FAIL"])
        r += 1
        for lbl, data in (("До исправлений", INTEG), ("После исправлений", INTEG_AFTER)):
            res_ = data["results"]
            put(ws, r, [lbl, len(res_), sum(t["status"] == "PASS" for t in res_), sum(t["status"] == "FAIL" for t in res_)])
            r += 1
        r += 1
        header(ws, r, ["Тест", "ID", "До", "После", "Результат после исправлений"])
        ws.merge_cells(start_row=r, start_column=5, end_row=r, end_column=8)
        r += 1
        top = r
        before = {t["id"]: t for t in INTEG["results"]}
        for t in INTEG_AFTER["results"]:
            was = before.get(t["id"], {}).get("status", "новый")
            if was == t["status"]:
                continue
            put(ws, r, [t["name"], t["id"], was, t["status"], t["actual"]])
            ws.merge_cells(start_row=r, start_column=5, end_row=r, end_column=8)
            ws.row_dimensions[r].height = 15 * max(2, len(t["actual"]) // 70 + 1)
            r += 1
        status_rules(ws, f"C{top}:D{r}")
        r += 1

    r = section(ws, r, "6. Выводы")
    for line_ in OPT.get("notes", []):
        ws.cell(row=r, column=1, value="• " + line_).font = F
        ws.cell(row=r, column=1).alignment = WRAP
        ws.merge_cells(start_row=r, start_column=1, end_row=r, end_column=8)
        ws.row_dimensions[r].height = 15 * max(2, len(line_) // 110 + 1)
        r += 1
    wb.move_sheet(ws, offset=-(len(wb.worksheets) - 1 - wb.sheetnames.index("Дефекты") - 1))

for w in wb.worksheets:
    w.sheet_properties.pageSetUpPr.fitToPage = True
    w.page_setup.fitToWidth, w.page_setup.fitToHeight = 1, 0  # по ширине страницы, высота — сколько нужно
    w.page_setup.orientation = "landscape"
wb.save(OUT)
print("saved", OUT)
