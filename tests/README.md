# Интеграционное и нагрузочное тестирование

Отчёт с результатами: `DOCs/testing/Отчет_тестирования.xlsx`, исходные данные: `DOCs/testing/results/`.

## Стенд

Приближен к `docker-compose.yml`: чистая БД MySQL 8, backend как исполняемый jar
(`java -Xms512m -Xmx1g -jar …`), фронтенд собран `VITE_API_URL=http://localhost:8080/api npm run build`
и раздаётся `node server.mjs` на :5173, сборка образов в симуляции с `LUCKFOX_SIM_DELAY=3`.

## Интеграционные тесты

```bash
pip install requests          # Python 3.10+
API_URL=http://localhost:8080/api WEB_URL=http://localhost:5173 \
STACK_CTL="bash ./stack-ctl.sh" \
MYSQL_CLI="mysql -uroot -proot luckfox_db" \
ARTIFACT_DIR=/path/to/output/images \
python3 tests/integration/run_integration.py [INT-01 INT-02 ...]
```

`STACK_CTL` — команда управления стендом, принимающая `backend-stop|backend-start|backend-kill|mysql-stop|mysql-start`.
Без `STACK_CTL`/`MYSQL_CLI`/`ARTIFACT_DIR` тесты отказоустойчивости и проверки БД/файлов помечаются SKIP.
Тесты INT-44…46 перезапускают backend и останавливают MySQL — только на тестовом стенде.

## Нагрузочные тесты (k6)

```bash
RESULTS_DIR=./results MYSQL_CLI="mysql -uroot -proot luckfox_db" tests/load/run_load.sh <профиль> [имя]
python3 tests/load/aggregate.py ./results            # → results/load_results.json
```

Профили: `smoke`, `browse`, `mixed`, `stress`, `spike`, `login`, `burst`, `download`, `soak`, `volume`
(описание — в шапке `tests/load/scenarios.js`). Объёмный тест: `volume` до и после
`mysql luckfox_db < tests/load/volume_seed.sql`, плюс `tests/load/heavy_queries.py`.

## Повторный прогон после оптимизации

Те же профили на исправленной версии и тех же данных, результаты — в отдельный каталог:

```bash
RESULTS_DIR=./results_after tests/load/run_load.sh breakpoint      # и stress, volume, mixed, login
python3 tests/load/aggregate.py ./results_after breakpoint stress volume_base volume mixed login
python3 tests/load/heavy_queries.py heavy_queries_after.json base  # и volume после volume_seed.sql
OUT=integration_results_after.json python3 tests/integration/run_integration.py
```

В `DOCs/testing/results/` они лежат как `load_results_after.json`, `heavy_queries_after.json`,
`integration_results_after.json`; по ним строится лист «Оптимизация» (сравнение до/после).

## Отчёт

```bash
pip install openpyxl
python3 tests/report/build_report.py DOCs/testing/results DOCs/testing/Отчет_тестирования.xlsx
```
