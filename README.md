# Luckfox Image Builder

Программная система автоматизированной (удалённой) сборки кастомных Linux-образов
для микрокомпьютеров **Luckfox Pico**. Пользователь конструирует образ в визуальном
редакторе, система разрешает зависимости компонентов, собирает образ на воркере и
отдаёт готовый `.img`.

## Структура проекта

| Каталог | Назначение |
|---------|-----------|
| `backend/` | Spring Boot 3 (Java 17): REST API, JWT-auth, оркестрация сборки |
| `frontend/` | React + Vite + Zustand: SPA (библиотеки, редактор образов/компонентов) |
| `scripts/` | `luckfox_build.sh` — обёртка сборки (реальный SDK / симуляция) |
| `luckfox-pico/` | Luckfox Pico SDK (Rockchip RV1103/RV1106) — **внешний, в репозиторий не входит** |
| `DOCs/` | Документация, диаграммы, макеты, схема БД |
| `План_разработки_курсового_проекта.md` | Переработанный план разработки |

> **Luckfox Pico SDK** (~12 ГБ) не хранится в этом репозитории. Он нужен только для
> **реальной** сборки образов (режим симуляции работает и без него). Чтобы получить SDK:
> ```bash
> git clone https://github.com/LuckfoxTECH/luckfox-pico.git
> ```
> Каталог `luckfox-pico/` ожидается в корне проекта (или укажите путь через `LUCKFOX_SDK_DIR`).

## Технологии

Java 17 / Spring Boot 3 (Web, Data JPA, Security, Validation) · MySQL 8 ·
React + Vite (React Router, Zustand, Axios) · Docker · Bash/Python (воркеры) · JMeter.

## Запуск

### Вариант A — Docker (всё разом)

```bash
./scripts/run-docker.sh          # рекомендуемый способ (сам соберёт артефакты и определит sudo)
./scripts/run-docker.sh -d       # то же, в фоне
./scripts/run-docker.sh down     # остановить
```

* Frontend: http://localhost:5173
* Backend:  http://localhost:8080
* MySQL:    localhost:3306 (рабочую схему создаёт Hibernate `ddl-auto=update`;
  `DOCs/этап3/java_working.sql` — документная модель данных)

**Как это собирается.** Docker-демон в WSL2 не имеет DNS внутри build-шагов, поэтому
артефакты (jar и `dist/`) собираются на хосте, а контейнеры их только запускают —
этим занимается `scripts/run-docker.sh` (он вызывает `mvn package` и `npm run build`,
затем `docker compose up --build`). Если у вас DNS в build работает — используйте
полноценные multi-stage сборки: `backend/Dockerfile.multistage`, `frontend/Dockerfile.multistage`.

Проверено end-to-end: каталог → логин → заказ сборки (с авторазрешением зависимостей)
→ статус READY → скачивание `.img` (реальный ext4-образ).

> **Про sudo.** Если пользователь не в группе `docker`, нужен `sudo` (запросит пароль) —
> запустите `./scripts/run-docker.sh` из своего терминала. Чтобы обходиться без sudo:
> `sudo usermod -aG docker $USER` и перелогиньтесь.

### Вариант B — локально

1. **БД:** поднимите MySQL и создайте схему:
   ```bash
   docker compose up -d db        # или вручную: mysql -u root -p < DOCs/этап3/java_working.sql
   ```
2. **Backend:**
   ```bash
   cd backend && mvn spring-boot:run
   ```
3. **Frontend:**
   ```bash
   cd frontend && npm install && npm run dev   # http://localhost:5173 (прокси /api → :8080)
   ```

## Учётные данные по умолчанию

При первом старте `DataSeeder` наполняет каталог и создаёт администратора:

* **admin@luckfox.io / Admin@123** (роли ADMIN+USER — доступен редактор компонентов).

## REST API (основное)

| Метод | Путь | Доступ |
|------|------|--------|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/users/me`, `/api/users/me/images` | auth |
| GET | `/api/users?search=&role=&page=&size=`, `/api/users/{id}` | ADMIN |
| POST/PUT/DELETE | `/api/users`, `/api/users/{id}` | ADMIN |
| GET | `/api/components?search=&type=&sort=&page=&size=`, `/api/components/{id}` | public |
| POST/PUT/DELETE | `/api/components`, `/api/components/{id}` | ADMIN |
| GET | `/api/component-types` | public |
| GET | `/api/images?search=&state=&board=&components=&sort=&page=&size=`, `/api/images/{id}` | public |
| POST | `/api/images/build` | auth |
| PUT/DELETE | `/api/images/{id}` (PUT — изменение с пересборкой) | ADMIN |
| GET | `/api/images/{id}/download` | auth |

Списки постраничные (нумерация страниц с 0, `size` ≤ 100), ответ:
`{ content, page, size, totalElements, totalPages }`. Поиск и фильтры применяются совместно.
`components=1,2` — образы, содержащие все перечисленные компоненты (с учётом транзитивных
зависимостей); `sort=price_asc|price_desc` — сортировка по стоимости.

## Сборка образов (toolchain)

Backend (`BuildService`, `@Async`) при заказе:
1. разрешает транзитивные зависимости и проверяет граф на циклы;
2. сохраняет образ `QUEUED` → `BUILDING`;
3. вызывает `scripts/luckfox_build.sh`;
4. при успехе → `READY` + `.img` доступен для скачивания, иначе → `FAILED` + лог.

Режимы `luckfox_build.sh`:
* **Симуляция** (по умолчанию) — собирает реальный ext4-`.img` с rootfs-манифестом
  и списком пакетов. Позволяет проверить весь конвейер без многочасовой кросс-сборки.
* **Реальный SDK** — `LUCKFOX_REAL_BUILD=1` (+ развёрнутые исходники SDK и кросс-тулчейн):
  выполняет `luckfox-pico/build.sh` и берёт `output/image/*.img`.

## Правила валидации (Auth)

* **Email:** стандартный формат (`@` и домен).
* **Пароль:** ≥8 символов, минимум одна цифра, строчная и заглавная буквы и спецсимвол (`@#$%^&+=!`).

## Статус сборки/верификации

* **Frontend:** `npm run build` проходит успешно.
* **Backend:** `mvn package` собирает исполняемый jar; код компилируется без ошибок.
* **Toolchain:** `scripts/luckfox_build.sh` (симуляция) проверен — создаёт валидный ext4-`.img`.
* **Runtime e2e (с MySQL):** запускается через `docker compose up --build`.
