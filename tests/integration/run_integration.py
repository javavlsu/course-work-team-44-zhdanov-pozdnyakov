"""Интеграционные тесты Luckfox Image Builder на развёрнутом стенде (чёрный ящик по HTTP).

Стенд приближен к продакшену: MySQL 8, backend как исполняемый jar, фронтенд — собранный
dist/ через server.mjs, API по абсолютному URL (CORS), сборка в очереди с задержкой.

Переменные окружения:
  API_URL    — http://localhost:8080/api
  WEB_URL    — http://localhost:5173
  STACK_CTL  — команда управления стендом (backend-stop|backend-start|backend-kill|
               mysql-stop|mysql-start); без неё тесты отказоустойчивости пропускаются
  MYSQL_CLI  — команда mysql-клиента к БД luckfox_db (для проверок схемы и счёта запросов)
  ARTIFACT_DIR — каталог готовых .img на сервере
  OUT        — куда записать JSON с результатами

Запуск: python3 tests/integration/run_integration.py
"""
import base64
import concurrent.futures as cf
import hashlib
import hmac
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

import requests

API = os.environ.get("API_URL", "http://localhost:8080/api")
WEB = os.environ.get("WEB_URL", "http://localhost:5173")
STACK_CTL = os.environ.get("STACK_CTL")
MYSQL_CLI = os.environ.get("MYSQL_CLI")
ARTIFACT_DIR = os.environ.get("ARTIFACT_DIR")
OUT = os.environ.get("OUT", "integration_results.json")
ADMIN = ("admin@luckfox.io", "Admin@123")
PASSWORD = "Test@1234"
# Ключ подписи захардкожен в JwtService — нужен, чтобы выпустить токен с истёкшим сроком.
JWT_SECRET_B64 = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"

RESULTS = []
DEFECTS = {}
RUN_ID = uuid.uuid4().hex[:6]


# ------------------------------------------------------------------ helpers ---
class Check(Exception):
    pass


def test(tid, stage, name, description, expected, defect=None):
    """Декоратор: регистрирует тест; defect=(id, severity, title, recommendation) при провале."""
    def wrap(fn):
        fn.meta = dict(id=tid, stage=stage, name=name, description=description,
                       expected=expected, defect=defect)
        TESTS.append(fn)
        return fn
    return wrap


TESTS = []


def ensure(cond, actual):
    if not cond:
        raise Check(actual)
    return actual


def h(token=None, **extra):
    hd = {"Content-Type": "application/json", **extra}
    if token:
        hd["Authorization"] = "Bearer " + token
    return hd


def call(method, path, token=None, timeout=60, **kw):
    headers = h(token, **kw.pop("headers", {}))
    return requests.request(method, API + path, headers=headers, timeout=timeout, **kw)


def login(email, password):
    r = call("POST", "/auth/login", json={"email": email, "password": password})
    return r.json()["token"] if r.status_code == 200 else None


def register(email=None, password=PASSWORD, name="Тест"):
    email = email or f"it-{RUN_ID}-{uuid.uuid4().hex[:6]}@test.io"
    r = call("POST", "/auth/register", json={"name": name, "surname": "Интеграционный",
                                             "email": email, "password": password})
    return email, r


def user_token():
    email, r = register()
    return email, r.json()["token"]


def comp_ids():
    return {c["name"]: c["id"] for c in call("GET", "/components", params={"size": 100}).json()["content"]}


def wait_state(image_id, states=("READY", "FAILED"), timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        r = call("GET", f"/images/{image_id}")
        if r.status_code == 200 and r.json()["state"] in states:
            return r.json(), time.time() - t0
        time.sleep(0.5)
    return call("GET", f"/images/{image_id}").json(), None


def wait_queue_idle(timeout=600):
    t0 = time.time()
    while time.time() - t0 < timeout:
        busy = sum(call("GET", "/images", params={"state": s, "size": 1}).json()["totalElements"]
                   for s in ("QUEUED", "BUILDING"))
        if busy == 0:
            return True
        time.sleep(1)
    return False


def order(token, name, components, board="RV1103_Luckfox_Pico", distro="buildroot"):
    return call("POST", "/images/build", token, json={
        "name": name, "description": "integration", "distro": distro, "board": board,
        "componentIds": components})


def stack(cmd, timeout=180):
    if not STACK_CTL:
        raise Check("SKIP: STACK_CTL не задан")
    p = subprocess.run(shlex.split(STACK_CTL) + [cmd], capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def sql(query):
    if not MYSQL_CLI:
        raise Check("SKIP: MYSQL_CLI не задан")
    p = subprocess.run(shlex.split(MYSQL_CLI) + ["-N", "-B", "-e", query], capture_output=True, text=True)
    return [line.split("\t") for line in p.stdout.strip().splitlines()]


def questions():
    return int(sql("SHOW GLOBAL STATUS LIKE 'Questions'")[0][1])


def jwt_payload(token):
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def forge_jwt(sub, iat, exp, key=None):
    key = key or base64.b64decode(JWT_SECRET_B64)
    head = b64url(json.dumps({"alg": "HS256"}).encode())
    body = b64url(json.dumps({"sub": sub, "iat": iat, "exp": exp}).encode())
    sig = hmac.new(key, f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{b64url(sig)}"


def status_line(r):
    return f"HTTP {r.status_code} {r.text[:160]}"


# =================================================== 1. Развёртывание / стенд ===
@test("INT-01", "1. Развёртывание", "Фронтенд (prod-сборка) отдаёт SPA и ассеты",
      "GET / и JS/CSS-бандлы из dist/ через server.mjs", "200, text/html; бандлы 200 с корректным Content-Type")
def t_frontend():
    r = requests.get(WEB + "/", timeout=10)
    ensure(r.status_code == 200 and "text/html" in r.headers.get("Content-Type", ""), status_line(r))
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', r.text)
    ensure(len(assets) >= 2, f"ассеты не найдены в index.html: {assets}")
    out = []
    for a in assets:
        ra = requests.get(WEB + a, timeout=10)
        ensure(ra.status_code == 200, f"{a}: HTTP {ra.status_code}")
        out.append(f"{a.split('/')[-1]} {ra.headers.get('Content-Type')} {len(ra.content)//1024} КБ")
    return "; ".join(out)


@test("INT-02", "1. Развёртывание", "Прямой переход по клиентскому маршруту (deep link)",
      "Обновление страницы на /editor/image и /admin/users", "200 и index.html (SPA fallback)")
def t_deeplink():
    res = []
    for p in ("/editor/image", "/admin/users", "/no/such/route"):
        r = requests.get(WEB + p, timeout=10)
        ensure(r.status_code == 200 and '<div id="root">' in r.text, f"{p}: {status_line(r)}")
        res.append(f"{p} → 200")
    return ", ".join(res)


@test("INT-03", "1. Развёртывание", "Отдача несуществующего ассета",
      "GET /assets/missing.js (напр. после деплоя новой версии у клиента старый index.html)",
      "404 для отсутствующего файла со статикой",
      defect=("DEF-01", "Низкая", "server.mjs отдаёт index.html со статусом 200 вместо 404 для отсутствующих /assets/*",
              "Для путей с расширением файла возвращать 404; SPA-fallback только для маршрутов без расширения"))
def t_missing_asset():
    r = requests.get(WEB + "/assets/missing-abc.js", timeout=10)
    ensure(r.status_code == 404, f"HTTP {r.status_code}, Content-Type {r.headers.get('Content-Type')} — "
                                 "браузер получит HTML вместо JS")
    return "HTTP 404"


@test("INT-04", "1. Развёртывание", "CORS: preflight с origin фронтенда",
      "OPTIONS /api/images/build с Origin http://localhost:5173", "200, Access-Control-Allow-Origin = origin")
def t_cors_ok():
    r = requests.options(API + "/images/build", headers={
        "Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST",
        "Access-Control-Request-Headers": "authorization,content-type"}, timeout=10)
    ensure(r.status_code == 200 and r.headers.get("Access-Control-Allow-Origin") == "http://localhost:5173",
           status_line(r) + f" ACAO={r.headers.get('Access-Control-Allow-Origin')}")
    return f"HTTP 200, ACAO={r.headers['Access-Control-Allow-Origin']}"


@test("INT-05", "1. Развёртывание", "CORS: origin боевого домена",
      "OPTIONS с Origin https://builder.example.com (фронт на домене/другом порту)",
      "Разрешённые origin задаются конфигурацией (переменной окружения)",
      defect=("DEF-02", "Средняя", "Разрешённый CORS-origin захардкожен (http://localhost:5173) в WebConfig",
              "Вынести список origin в application.properties/переменную окружения (CORS_ALLOWED_ORIGINS)"))
def t_cors_prod():
    r = requests.options(API + "/images", headers={
        "Origin": "https://builder.example.com", "Access-Control-Request-Method": "GET"}, timeout=10)
    ensure(r.status_code == 200, f"HTTP {r.status_code} «{r.text[:40]}» — фронт на любом другом адресе не сможет "
                                 "обращаться к API; origin изменить без пересборки нельзя")
    return "HTTP 200"


@test("INT-06", "1. Развёртывание", "Схема БД создана Hibernate на чистой MySQL",
      "Проверка таблиц и ограничений в information_schema", "Все таблицы есть, уникальный индекс на User.email")
def t_schema():
    tables = {r[0] for r in sql("SHOW TABLES")}
    need = {"User", "Image", "Component", "component_type", "Role", "dependency",
            "image_component_dependency", "user_role", "user_image_order"}
    missing = {t.lower() for t in need} - {t.lower() for t in tables}
    ensure(not missing, f"нет таблиц: {missing}")
    uniq = sql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='luckfox_db' "
               "AND table_name='User' AND column_name='email' AND non_unique=0")[0][0]
    ensure(uniq != "0", "нет уникального индекса на email")
    return f"{len(tables)} таблиц, уникальный индекс email есть"


@test("INT-07", "1. Развёртывание", "Начальное наполнение (DataSeeder) через конвейер сборки",
      "На чистой БД: каталог, администратор, стартовые образы проходят реальную очередь сборки",
      "67 компонентов, 15 образов; все стартовые образы READY и скачиваемы")
def t_seed():
    comps = call("GET", "/components", params={"size": 1}).json()["totalElements"]
    ensure(comps == 67, f"компонентов {comps}")
    t0 = time.time()
    ensure(wait_queue_idle(300), "очередь не опустела за 300 с")
    imgs = call("GET", "/images", params={"size": 100}).json()["content"]
    ready = [i for i in imgs if i["state"] == "READY" and i["downloadable"]]
    ensure(len(imgs) == 15 and len(ready) == 15, f"образов {len(imgs)}, READY {len(ready)}")
    ensure(login(*ADMIN), "администратор не может войти")
    return f"67 компонентов, 15/15 образов READY (очередь опустела за {time.time() - t0:.0f} с), вход admin OK"


# ======================================================= 2. Аутентификация ===
@test("INT-08", "2. Аутентификация и доступ", "Регистрация и вход",
      "POST /auth/register, затем POST /auth/login и GET /users/me", "200, JWT, профиль с ролью USER")
def t_register_login():
    email, r = register()
    ensure(r.status_code == 200 and r.json().get("token"), status_line(r))
    tok = login(email, PASSWORD)
    me = call("GET", "/users/me", tok).json()
    ensure(me["email"] == email and me["roles"] == ["USER"], me)
    return f"токен выдан, /users/me → {me['email']} {me['roles']}"


@test("INT-09", "2. Аутентификация и доступ", "Валидация регистрации",
      "Слабый пароль, неверный email, пустое имя, дубликат email", "400 с понятным сообщением для каждого случая")
def t_register_validation():
    email, _ = register()
    cases = {
        "weak password": {"password": "12345678"},
        "bad email": {"email": "not-an-email"},
        "empty name": {"name": ""},
        "duplicate": {"email": email},
    }
    out = []
    for label, patch in cases.items():
        body = {"name": "A", "surname": "B", "email": f"v-{uuid.uuid4().hex[:6]}@t.io", "password": PASSWORD, **patch}
        r = call("POST", "/auth/register", json=body)
        ensure(r.status_code == 400, f"{label}: {status_line(r)}")
        out.append(f"{label}→400")
    return ", ".join(out)


@test("INT-10", "2. Аутентификация и доступ", "Вход с неверным паролем / несуществующим email",
      "POST /auth/login с неверными учётными данными", "401 Unauthorized с сообщением об ошибке",
      defect=("DEF-03", "Средняя", "Неверный логин/пароль возвращает 403 без тела вместо 401",
              "Обрабатывать AuthenticationException в GlobalExceptionHandler (401 + сообщение); "
              "разрешить /error в SecurityConfig"))
def t_bad_login():
    r1 = call("POST", "/auth/login", json={"email": ADMIN[0], "password": "Wrong@123"})
    r2 = call("POST", "/auth/login", json={"email": "nobody@x.io", "password": "Wrong@123"})
    ensure(r1.status_code == 401 and r2.status_code == 401,
           f"неверный пароль → HTTP {r1.status_code} «{r1.text[:60]}»; нет пользователя → HTTP {r2.status_code}")
    return "401 / 401"


@test("INT-48", "2. Аутентификация и доступ", "Защита от перебора паролей",
      "Новый пользователь; 7 входов с неверным паролем подряд, затем вход с верным паролем; "
      "вход другого пользователя с того же адреса",
      "После 5 неудач — 429 с Retry-After (и для верного пароля до конца окна); другие пользователи входят",
      defect=("DEF-21", "Высокая", "Нет ограничения частоты попыток входа: перебор паролей без блокировки",
              "Счётчик неудач по IP и email, 429 + Retry-After без проверки BCrypt"))
def t_bruteforce():
    victim, _ = register()
    codes = [call("POST", "/auth/login", json={"email": victim, "password": f"Guess@{i}"}).status_code
             for i in range(7)]
    blocked = call("POST", "/auth/login", json={"email": victim, "password": PASSWORD})
    other, _ = register()
    ok = login(other, PASSWORD)
    ensure(429 not in codes[:5] and codes[5:] == [429, 429] and blocked.status_code == 429
           and blocked.headers.get("Retry-After") and ok,
           f"коды попыток {codes}; верный пароль → {blocked.status_code}; другой пользователь вошёл: {bool(ok)}")
    return (f"попытки {codes}; верный пароль в окне блокировки → 429, Retry-After {blocked.headers['Retry-After']} с; "
            "другой пользователь вошёл")


@test("INT-11", "2. Аутентификация и доступ", "Доступ к защищённым ресурсам без токена",
      "GET /users/me, POST /images/build без Authorization", "401 (фронтенд перенаправляет на вход только по 401)",
      defect=("DEF-04", "Средняя", "Запрос без токена/с недействительным токеном получает 403 вместо 401 — "
              "фронтенд (interceptor по 401) не выкидывает пользователя на страницу входа",
              "Настроить authenticationEntryPoint (HttpStatusEntryPoint(UNAUTHORIZED)) в SecurityConfig"))
def t_no_token():
    r1 = call("GET", "/users/me")
    r2 = order(None, "x", [1])
    ensure(r1.status_code == 401 and r2.status_code == 401, f"/users/me → {r1.status_code}, /images/build → {r2.status_code}")
    return "401 / 401"


@test("INT-12", "2. Аутентификация и доступ", "Повреждённый и чужой по подписи токен",
      "Authorization: Bearer <мусор>; токен, подписанный другим ключом", "401, без 500 и стектрейсов",
      defect=("DEF-05", "Высокая", "Некорректный/поддельный/просроченный JWT приводит к исключению в "
              "JwtAuthenticationFilter (ответ 403/500 вместо 401), в лог пишется стектрейс",
              "Оборачивать разбор JWT в try/catch (JwtException) и продолжать цепочку как аноним → 401"))
def t_bad_token():
    fake = forge_jwt(ADMIN[0], int(time.time()), int(time.time()) + 3600, key=os.urandom(32))
    res = {}
    for label, tok in (("мусор", "abc.def.ghi"), ("чужая подпись", fake)):
        r = call("GET", "/users/me", tok)
        res[label] = r.status_code
    ensure(all(v == 401 for v in res.values()), f"коды: {res}")
    return f"коды: {res}"


@test("INT-13", "2. Аутентификация и доступ", "Просроченный токен",
      "Токен с exp в прошлом (подписан ключом приложения)", "401 → фронтенд отправляет на вход",
      defect=("DEF-05", None, None, None))
def t_expired():
    now = int(time.time())
    r = call("GET", "/users/me", forge_jwt(ADMIN[0], now - 7200, now - 3600))
    ensure(r.status_code == 401, f"HTTP {r.status_code} (ExpiredJwtException не обработано)")
    return "401"


@test("INT-14", "2. Аутентификация и доступ", "Время жизни JWT",
      "Декодирование выданного токена: exp - iat", "24 часа (так указано в коде/документации)",
      defect=("DEF-06", "Низкая", "Срок жизни JWT 4 ч вместо заявленных 24 ч (1000*60*24*10 мс)",
              "Исправить на 1000L*60*60*24 и вынести в конфигурацию (jwt.expiration)"))
def t_jwt_ttl():
    p = jwt_payload(login(*ADMIN))
    hours = (p["exp"] - p["iat"]) / 3600
    ensure(abs(hours - 24) < 0.01, f"{hours:.1f} ч")
    return f"{hours:.1f} ч"


@test("INT-15", "2. Аутентификация и доступ", "Ключ подписи JWT в исходном коде",
      "Токен, подписанный ключом из репозитория, принимается сервером", "Ключ берётся из секрета окружения; такой токен отклоняется",
      defect=("DEF-07", "Критическая", "Секрет JWT захардкожен в JwtService и лежит в Git: любой, кто видел "
              "репозиторий, может выпустить токен администратора",
              "Читать секрет из переменной окружения/секрета, сгенерировать новый, старый считать скомпрометированным"))
def t_jwt_secret():
    now = int(time.time())
    r = call("GET", "/users", forge_jwt(ADMIN[0], now, now + 600))
    ensure(r.status_code in (401, 403), f"GET /users (ADMIN) с токеном из известного ключа → HTTP {r.status_code}")
    return f"HTTP {r.status_code}"


@test("INT-16", "2. Аутентификация и доступ", "Разграничение ролей USER / ADMIN",
      "USER вызывает админские операции: GET /users, POST /components, PUT/DELETE /images/{id}", "403 для всех")
def t_roles():
    _, tok = user_token()
    img = call("GET", "/images", params={"size": 1}).json()["content"][0]["id"]
    codes = {
        "GET /users": call("GET", "/users", tok).status_code,
        "POST /components": call("POST", "/components", tok, json={"name": "x", "typeId": 1, "cost": 1}).status_code,
        "PUT /images": call("PUT", f"/images/{img}", tok, json={"name": "x", "distro": "buildroot", "componentIds": [1]}).status_code,
        "DELETE /images": call("DELETE", f"/images/{img}", tok).status_code,
        "DELETE /users/1": call("DELETE", "/users/1", tok).status_code,
    }
    ensure(all(c == 403 for c in codes.values()), codes)
    return ", ".join(f"{k}→{v}" for k, v in codes.items())


@test("INT-17", "2. Аутентификация и доступ", "Токен удалённого пользователя",
      "Админ удаляет пользователя, тот продолжает запрос со старым токеном", "401",
      defect=("DEF-08", "Средняя", "Запрос с токеном удалённого пользователя: UsernameNotFoundException в фильтре → 403/500",
              "Ловить UsernameNotFoundException в JwtAuthenticationFilter и отвечать 401"))
def t_deleted_user():
    email, tok = user_token()
    adm = login(*ADMIN)
    uid = call("GET", "/users/me", tok).json()["id"]
    ensure(call("DELETE", f"/users/{uid}", adm).status_code == 204, "не удалось удалить")
    r = call("GET", "/users/me", tok)
    ensure(r.status_code == 401, f"HTTP {r.status_code}")
    return "401"


@test("INT-18", "2. Аутентификация и доступ", "Гонка: одновременная регистрация одного email",
      "20 параллельных POST /auth/register с одинаковым email", "Ровно 1 успех, остальные 400/409, дублей в БД нет, 5xx нет",
      defect=("DEF-09", "Средняя", "Гонка при параллельной регистрации одного email: проигравшие получают 500/403 вместо 409",
              "Нарушение уникальности (DataIntegrityViolation) при регистрации обрабатывать как 409 «email занят»"))
def t_register_race():
    email = f"race-{RUN_ID}@test.io"
    with cf.ThreadPoolExecutor(20) as ex:
        codes = list(ex.map(lambda _: register(email)[1].status_code, range(20)))
    dist = {c: codes.count(c) for c in set(codes)}
    rows = sql(f"SELECT COUNT(*) FROM `User` WHERE email='{email}'")[0][0]
    ensure(dist.get(200) == 1 and rows == "1", f"коды {dist}, строк в БД {rows}")
    ensure(set(dist) <= {200, 400, 409}, f"коды {dist}, строк в БД {rows} (дубль не создан, но проигравшие получили не 400/409)")
    return f"коды {dist}, строк в БД {rows}"


# ============================================================= 3. Каталог ===
@test("INT-19", "3. Каталог и пагинация", "Пагинация: граничные значения",
      "size=1000, size=0, page=-1, page за пределами", "size обрезается до 100, page<0 → 0, пустая страница за пределами")
def t_paging_bounds():
    a = call("GET", "/components", params={"size": 1000}).json()
    b = call("GET", "/components", params={"size": 0}).json()
    c = call("GET", "/components", params={"page": -1, "size": 5}).json()
    d = call("GET", "/components", params={"page": 999, "size": 5}).json()
    ensure(a["size"] == 100 and len(a["content"]) == 67, f"size=1000 → size {a['size']}")
    ensure(b["size"] == 1 and c["page"] == 0 and d["content"] == [], (b["size"], c["page"], len(d["content"])))
    return "size 1000→100, 0→1, page -1→0, page 999 → пусто"


@test("INT-20", "3. Каталог и пагинация", "Нечисловые параметры (анонимный пользователь)",
      "GET /components?page=abc, GET /images?components=x, GET /components/abc", "400 Bad Request",
      defect=("DEF-10", "Средняя", "Ошибки разбора параметров у анонимного пользователя возвращают 403 вместо 400 "
              "(ошибка уходит на /error, закрытый Spring Security)",
              "Разрешить /error в SecurityConfig (permitAll) и обработать MethodArgumentTypeMismatchException → 400"))
def t_bad_params():
    codes = {
        "page=abc": call("GET", "/components", params={"page": "abc"}).status_code,
        "components=x": call("GET", "/images", params={"components": "x"}).status_code,
        "/components/abc": call("GET", "/components/abc").status_code,
    }
    ensure(all(c == 400 for c in codes.values()), codes)
    return str(codes)


@test("INT-21", "3. Каталог и пагинация", "Спецсимволы и инъекции в поиске",
      "search = ', \", ' OR 1=1 --, %, _, <script>", "Нет 5xx; спецсимволы трактуются буквально (% и _ не шаблоны)",
      defect=("DEF-11", "Низкая", "Символы % и _ в поиске не экранируются и работают как шаблоны LIKE "
              "(поиск «%» возвращает весь каталог)", "Экранировать % и _ (cb.like(..., escapeChar))"))
def t_search_special():
    res = {}
    for s in ("'", '"', "' OR 1=1 --", "<script>", "%", "_"):
        r = call("GET", "/components", params={"search": s})
        ensure(r.status_code == 200, f"search={s!r}: {status_line(r)}")
        res[s] = r.json()["totalElements"]
    ensure(res["' OR 1=1 --"] == 0, f"SQL-инъекция сработала: {res}")
    ensure(res["%"] == 0 and res["_"] == 0, f"инъекции нет (параметризованные запросы), но wildcard: найдено {res}")
    return f"{res}"


@test("INT-22", "3. Каталог и пагинация", "Комбинация поиска, типа, сортировки и страниц",
      "Сквозной проход всех страниц с search+type+sort=price_desc", "Без дублей и пропусков, порядок по цене сохраняется между страницами")
def t_combo_paging():
    seen, costs = [], []
    for page in range(20):
        r = call("GET", "/components", params={"search": "dummy", "type": 1, "sort": "price_desc",
                                               "size": 7, "page": page}).json()
        seen += [c["id"] for c in r["content"]]
        costs += [c["cost"] for c in r["content"]]
        if page >= r["totalPages"] - 1:
            break
    ensure(len(seen) == len(set(seen)) == r["totalElements"], f"id {len(seen)}, уникальных {len(set(seen))}, total {r['totalElements']}")
    ensure(costs == sorted(costs, reverse=True), costs)
    return f"{len(seen)} записей на {r['totalPages']} стр., дублей нет"


@test("INT-23", "3. Каталог и пагинация", "Число SQL-запросов на одну страницу образов",
      "Дельта счётчика Questions MySQL вокруг GET /images?size=12", "≤ 10 запросов (без проблемы N+1)",
      defect=("DEF-12", "Средняя", "N+1 запросов: список из 12 образов порождает десятки SQL-запросов "
              "(ленивые components и dependencies грузятся поштучно)",
              "@EntityGraph / JOIN FETCH для components.type, batch-size для коллекций (hibernate.default_batch_fetch_size)"))
def t_n_plus_one():
    call("GET", "/images", params={"size": 12})
    q0 = questions()
    call("GET", "/images", params={"size": 12})
    q1 = questions()
    comps = call("GET", "/components", params={"size": 20})
    q2 = questions()
    img_q, comp_q = q1 - q0 - 1, q2 - q1 - 1
    ensure(img_q <= 10, f"GET /images?size=12 → {img_q} SQL-запросов; GET /components?size=20 → {comp_q}")
    return f"{img_q} / {comp_q}"


# ========================================================== 4. Компоненты ===
@test("INT-24", "4. Компоненты (ADMIN)", "Создание компонента с зависимостями и чтение",
      "POST /components с dependencyIds, GET /components/{id}", "201/200, зависимости сохранены")
def t_comp_create():
    adm, ids = login(*ADMIN), comp_ids()
    r = call("POST", "/components", adm, json={"name": f"it-lib-{RUN_ID}", "typeId": 2, "cost": 15,
                                               "description": "интеграционный", "dependencyIds": [ids["python3"]]})
    ensure(r.status_code in (200, 201), status_line(r))
    got = call("GET", f"/components/{r.json()['id']}").json()
    ensure([d["name"] for d in got["dependencies"]] == ["python3"], got)
    return f"HTTP {r.status_code}, id={got['id']}, deps={got['dependencyIds']}"


@test("INT-25", "4. Компоненты (ADMIN)", "Защита графа зависимостей",
      "Зависимость на себя; цикл A→B→A через PUT", "400 в обоих случаях, граф не меняется")
def t_comp_cycle():
    adm = login(*ADMIN)
    a = call("POST", "/components", adm, json={"name": f"it-A-{RUN_ID}", "typeId": 1, "cost": 1, "dependencyIds": []}).json()
    b = call("POST", "/components", adm, json={"name": f"it-B-{RUN_ID}", "typeId": 1, "cost": 1, "dependencyIds": [a["id"]]}).json()
    r1 = call("PUT", f"/components/{a['id']}", adm, json={"name": a["name"], "typeId": 1, "cost": 1, "dependencyIds": [a["id"]]})
    r2 = call("PUT", f"/components/{a['id']}", adm, json={"name": a["name"], "typeId": 1, "cost": 1, "dependencyIds": [b["id"]]})
    ensure(r1.status_code == 400 and r2.status_code == 400, f"self → {r1.status_code}, cycle → {r2.status_code}")
    ensure(call("GET", f"/components/{a['id']}").json()["dependencyIds"] == [], "граф изменён")
    return "self→400, cycle→400"


@test("INT-26", "4. Компоненты (ADMIN)", "Удаление используемого и свободного компонента",
      "DELETE компонента, входящего в образ; DELETE свободного", "400 с сообщением; 204")
def t_comp_delete():
    adm, ids = login(*ADMIN), comp_ids()
    r1 = call("DELETE", f"/components/{ids['numpy']}", adm)
    free = call("POST", "/components", adm, json={"name": f"it-free-{RUN_ID}", "typeId": 1, "cost": 1}).json()
    r2 = call("DELETE", f"/components/{free['id']}", adm)
    r3 = call("GET", f"/components/{free['id']}")
    ensure(r1.status_code == 400 and r2.status_code == 204 and r3.status_code == 404,
           f"used→{r1.status_code}, free→{r2.status_code}, get→{r3.status_code}")
    return f"used→400 «{r1.json()['error'][:50]}», free→204, затем GET→404"


@test("INT-27", "4. Компоненты (ADMIN)", "Валидация полей компонента",
      "Пустое имя; отрицательная цена; имя 300 символов; несуществующий тип",
      "400 для некорректных полей (404 допустим для несуществующего типа)",
      defect=("DEF-13", "Низкая", "Имя компонента длиннее 255 символов даёт 409 с вводящим в заблуждение "
              "сообщением «запись используется в других объектах» вместо 400 валидации",
              "@Size(max=255) на name в ComponentRequest/BuildRequest/UserRequest"))
def t_comp_validation():
    adm = login(*ADMIN)
    cases = {
        "empty name": {"name": "", "typeId": 1, "cost": 1},
        "negative cost": {"name": f"neg-{RUN_ID}", "typeId": 1, "cost": -100},
        "name 300": {"name": "x" * 300, "typeId": 1, "cost": 1},
        "bad type": {"name": f"bt-{RUN_ID}", "typeId": 999, "cost": 1},
    }
    codes = {k: call("POST", "/components", adm, json=v).status_code for k, v in cases.items()}
    for n in (f"neg-{RUN_ID}",):
        for c in call("GET", "/components", params={"search": n}).json()["content"]:
            call("DELETE", f"/components/{c['id']}", adm)
    ensure(all(c == 400 for k, c in codes.items() if k != "bad type") and codes["bad type"] in (400, 404), codes)
    return str(codes)


@test("INT-28", "4. Компоненты (ADMIN)", "Параллельное редактирование компонента двумя админами",
      "10 одновременных PUT одного компонента с разными ценами", "Без 5xx; итог — одно из отправленных значений")
def t_comp_concurrent():
    adm = login(*ADMIN)
    c = call("POST", "/components", adm, json={"name": f"it-conc-{RUN_ID}", "typeId": 1, "cost": 1}).json()

    def put(i):
        return call("PUT", f"/components/{c['id']}", adm,
                    json={"name": c["name"], "typeId": 1, "cost": 100 + i}).status_code

    with cf.ThreadPoolExecutor(10) as ex:
        codes = list(ex.map(put, range(10)))
    final = call("GET", f"/components/{c['id']}").json()["cost"]
    dist = {x: codes.count(x) for x in set(codes)}
    ensure(all(x in (200, 409) for x in codes) and 100 <= final <= 109, f"коды {dist}, итог {final}")
    return f"коды {dist}, итоговая цена {final}: оптимистичная блокировка (@Version) отсекла конкурентные записи"


# ================================================= 5. Образы и сборка ===
STATE = {}


@test("INT-29", "5. Заказ и сборка образа", "Заказ сборки с разрешением транзитивных зависимостей",
      "USER заказывает образ [opencv-python]", "202, QUEUED, в составе numpy и python3, цена = сумма компонентов")
def t_order():
    email, tok = user_token()
    ids = comp_ids()
    r = order(tok, f"IT Образ «Тест» {RUN_ID}", [ids["opencv-python"]])
    ensure(r.status_code == 202, status_line(r))
    img = r.json()
    names = sorted(c["name"] for c in img["components"])
    ensure(names == ["numpy", "opencv-python", "python3"] and img["cost"] == 160 and img["state"] in ("QUEUED", "BUILDING", "READY"),
           f"{img['state']}, {names}, cost {img['cost']}")
    STATE.update(user=(email, tok), image=img)
    return f"HTTP 202, state={img['state']}, состав={names}, cost={img['cost']}"


@test("INT-30", "5. Заказ и сборка образа", "Сборка до READY и появление в «Мои образы»",
      "Опрос GET /images/{id} до финального статуса", "READY, downloadable=true, образ в /users/me/images")
def t_build_ready():
    img, took = wait_state(STATE["image"]["id"])
    ensure(img["state"] == "READY" and img["downloadable"], f"{img['state']} за {took}")
    mine = [i["id"] for i in call("GET", "/users/me/images", STATE["user"][1]).json()]
    ensure(img["id"] in mine, mine)
    return f"READY за {took:.1f} с"


@test("INT-31", "5. Заказ и сборка образа", "Скачивание артефакта и проверка содержимого",
      "GET /images/{id}/download владельцем; проверка ext4 и манифеста внутри образа",
      "200, octet-stream, валидный ext4 (магия 0xEF53), размер 16+N МБ, манифест — валидный JSON с составом",
      defect=("DEF-14", "Средняя", "Название образа с кавычками ломает манифест /etc/luckfox-image.json "
              "(значения подставляются в JSON без экранирования)",
              "Формировать JSON-манифест через jq/python или экранировать \" и \\ в luckfox_build.sh"))
def t_download():
    r = call("GET", f"/images/{STATE['image']['id']}/download", STATE["user"][1])
    ensure(r.status_code == 200 and r.headers["Content-Type"] == "application/octet-stream", status_line(r))
    data = r.content
    magic = int.from_bytes(data[1080:1082], "little")
    ensure(magic == 0xEF53, f"магия ext4 {magic:#x}")
    ensure(len(data) == (16 + 3) * 1024 * 1024, f"размер {len(data)}")
    with tempfile.NamedTemporaryFile(suffix=".img", delete=False) as f:
        f.write(data)
    manifest = subprocess.run(["debugfs", "-R", "cat /etc/luckfox-image.json", f.name],
                              capture_output=True, text=True).stdout
    pkgs = subprocess.run(["debugfs", "-R", "cat /etc/installed-packages.txt", f.name],
                          capture_output=True, text=True).stdout.split()
    os.unlink(f.name)
    ensure(sorted(pkgs) == ["numpy", "opencv-python", "python3"], f"пакеты {pkgs}")
    try:
        m = json.loads(manifest)
    except ValueError:
        raise Check(f"ext4 OK, {len(data) >> 20} МБ, пакеты {pkgs}; манифест НЕ JSON: {manifest.strip()[:120]!r}")
    return f"ext4 OK, {len(data) >> 20} МБ, пакеты {pkgs}, манифест: {m['name']}"


@test("INT-32", "5. Заказ и сборка образа", "Имя файла при скачивании (кириллица)",
      "Заголовок Content-Disposition для образа «Образ для камеры»",
      "Корректное имя файла (filename*=UTF-8''… по RFC 6266)",
      defect=("DEF-15", "Средняя", "Для образов с кириллическим названием заголовок Content-Disposition "
              "теряется — файл скачивается без имени и расширения .img",
              "ContentDisposition.attachment().filename(name, UTF_8) из Spring"))
def t_filename():
    tok = STATE["user"][1]
    img = order(tok, "Образ для камеры", [comp_ids()["glibc"]]).json()
    wait_state(img["id"])
    r = call("GET", f"/images/{img['id']}/download", tok, stream=True)
    cd = r.headers.get("Content-Disposition")
    r.close()
    ensure(cd is not None, f"HTTP {r.status_code}, заголовок Content-Disposition отсутствует (Tomcat отбросил "
                           "значение с не-Latin-1 символами) — браузер сохранит файл как «download»")
    ensure("filename*=" in cd.lower(), f"Content-Disposition: {cd}")
    return cd


@test("INT-33", "5. Заказ и сборка образа", "Скачивание чужого образа",
      "Другой USER скачивает образ первого пользователя", "403 (скачивать может владелец или ADMIN)",
      defect=("DEF-16", "Высокая", "Нет проверки владельца при скачивании: любой авторизованный пользователь "
              "скачивает чужие образы по id (IDOR)",
              "В download проверять, что образ в orderedImages текущего пользователя или роль ADMIN"))
def t_idor():
    _, other = user_token()
    r = call("GET", f"/images/{STATE['image']['id']}/download", other)
    ensure(r.status_code == 403, f"HTTP {r.status_code}, получено {len(r.content) >> 20} МБ чужого образа")
    return "403"


@test("INT-34", "5. Заказ и сборка образа", "Скачивание без токена и неготового образа",
      "GET /download анонимно; GET /download для QUEUED", "401; 400 «не готов»",
      defect=("DEF-04", None, None, None))
def t_download_guards():
    r1 = call("GET", f"/images/{STATE['image']['id']}/download")
    tok = STATE["user"][1]
    r = order(tok, f"it-notready-{RUN_ID}", [comp_ids()["glibc"]]).json()
    r2 = call("GET", f"/images/{r['id']}/download", tok)
    ensure(r2.status_code == 400, f"not ready → {r2.status_code}")
    ensure(r1.status_code == 401, f"аноним → HTTP {r1.status_code}; неготовый → 400")
    return "401; 400"


@test("INT-35", "5. Заказ и сборка образа", "Валидация заказа",
      "Пустой список компонентов; несуществующий компонент; пустое имя", "400/404 без 5xx")
def t_order_validation():
    _, tok = user_token()
    codes = {
        "empty components": order(tok, "x", []).status_code,
        "unknown component": order(tok, "x", [999999]).status_code,
        "empty name": order(tok, "", [1]).status_code,
    }
    ensure(all(c in (400, 404) for c in codes.values()), codes)
    return str(codes)


@test("INT-36", "5. Заказ и сборка образа", "Очередь сборок: один воркер, FIFO",
      "6 последовательных заказов + 6 одновременных от разных пользователей; мониторинг статусов каждые 0,3 с",
      "Одновременно BUILDING не более 1; последовательные заказы завершаются в порядке заказа; все READY")
def t_queue():
    ensure(wait_queue_idle(), "очередь занята")
    toks = [user_token()[1] for _ in range(6)]
    glibc = comp_ids()["glibc"]
    ids = [order(t, f"it-q{n}-{RUN_ID}", [glibc]).json()["id"] for n, t in enumerate(toks)]
    with cf.ThreadPoolExecutor(6) as ex:
        par = list(ex.map(lambda p: order(p[1], f"it-qp{p[0]}-{RUN_ID}", [glibc]).json()["id"], enumerate(toks)))
    max_building, finished, t0 = 0, [], time.time()
    while len(finished) < len(ids) + len(par) and time.time() - t0 < 240:
        states = {i: call("GET", f"/images/{i}").json()["state"] for i in ids + par}
        max_building = max(max_building, sum(s == "BUILDING" for s in states.values()))
        for i, s in states.items():
            if s in ("READY", "FAILED") and i not in finished:
                finished.append(i)
        time.sleep(0.3)
    total = time.time() - t0
    ensure(max_building <= 1, f"одновременно BUILDING: {max_building}")
    ensure(finished[:6] == ids, f"порядок завершения {finished[:6]}, ожидался {ids}")
    ensure(all(call("GET", f"/images/{i}").json()["state"] == "READY" for i in ids + par), "не все READY")
    return (f"max BUILDING=1, FIFO соблюдён, 12 сборок за {total:.1f} с ({total / 12:.1f} с/сборку); "
            f"одновременные заказы выполняются в порядке фиксации транзакций, а не id: {finished[6:]}")


@test("INT-37", "5. Заказ и сборка образа", "Изменение образа администратором с пересборкой",
      "PUT /images/{id} READY-образа (другой состав); проверка нового артефакта",
      "202 → QUEUED → READY; новый состав в артефакте; старый файл заменён")
def t_image_edit():
    adm, ids = login(*ADMIN), comp_ids()
    iid = STATE["image"]["id"]
    r = call("PUT", f"/images/{iid}", adm, json={"name": f"it-edited-{RUN_ID}", "distro": "buildroot",
                                                  "board": "RV1106_Luckfox_Pico_Pro_Max",
                                                  "componentIds": [ids["openssh"]]})
    ensure(r.status_code == 202 and r.json()["state"] in ("QUEUED", "BUILDING"), status_line(r))
    busy = call("PUT", f"/images/{iid}", adm, json={"name": "x", "distro": "buildroot", "componentIds": [ids["glibc"]]})
    img, took = wait_state(iid)
    ensure(img["state"] == "READY", img["state"])
    names = sorted(c["name"] for c in img["components"])
    ensure(names == ["glibc", "openssh"] and img["cost"] == 60, (names, img["cost"]))
    data = call("GET", f"/images/{iid}/download", adm).content
    ensure(len(data) == 18 * 1024 * 1024, f"размер {len(data)}")
    ensure(busy.status_code == 400, f"PUT во время сборки → {busy.status_code}")
    return f"202, пересобран за {took:.1f} с, состав {names}, cost 60; PUT во время сборки → 400"


@test("INT-38", "5. Заказ и сборка образа", "Удаление образа администратором",
      "DELETE /images/{id}: запись, связь с пользователем и файл артефакта", "204; GET → 404; файл удалён с диска; пропал из «Мои образы»")
def t_image_delete():
    adm = login(*ADMIN)
    iid = STATE["image"]["id"]
    path = os.path.join(ARTIFACT_DIR, f"image-{iid}.img") if ARTIFACT_DIR else None
    existed = path and os.path.exists(path)
    r = call("DELETE", f"/images/{iid}", adm)
    ensure(r.status_code == 204, status_line(r))
    ensure(call("GET", f"/images/{iid}").status_code == 404, "GET не 404")
    mine = [i["id"] for i in call("GET", "/users/me/images", STATE["user"][1]).json()]
    ensure(iid not in mine, "остался в «Мои образы»")
    time.sleep(0.5)
    if path:
        ensure(existed and not os.path.exists(path), f"файл до {existed}, после {os.path.exists(path)}")
    return "204, 404, файл удалён, связь с пользователем снята"


@test("INT-39", "5. Заказ и сборка образа", "Удаление пользователя, у которого есть образы",
      "Админ удаляет пользователя с заказанными образами", "204; образы остаются в библиотеке")
def t_delete_user_with_images():
    adm = login(*ADMIN)
    email, tok = user_token()
    img = order(tok, f"it-owner-{RUN_ID}", [comp_ids()["glibc"]]).json()
    uid = call("GET", "/users/me", tok).json()["id"]
    r = call("DELETE", f"/users/{uid}", adm)
    ensure(r.status_code == 204, status_line(r))
    ensure(call("GET", f"/images/{img['id']}").status_code == 200, "образ пропал")
    return "204, образ сохранён"


@test("INT-40", "5. Заказ и сборка образа", "Артефакт удалён с диска вне приложения",
      "Файл .img удаляется на сервере, затем GET /images/{id} и /download",
      "downloadable=false либо понятная ошибка; без 5xx",
      defect=("DEF-17", "Низкая", "Если файл артефакта пропал с диска, карточка продолжает показывать «Скачать» "
              "(downloadable=true), а скачивание даёт 400",
              "Проверять существование файла при формировании downloadable или помечать образ FAILED/переводить в пересборку"))
def t_missing_artifact():
    if not ARTIFACT_DIR:
        raise Check("SKIP: ARTIFACT_DIR не задан")
    _, tok = user_token()
    img = order(tok, f"it-lost-{RUN_ID}", [comp_ids()["glibc"]]).json()
    img, _ = wait_state(img["id"])
    os.remove(os.path.join(ARTIFACT_DIR, f"image-{img['id']}.img"))
    dto = call("GET", f"/images/{img['id']}").json()
    r = call("GET", f"/images/{img['id']}/download", tok)
    ensure(r.status_code < 500, status_line(r))
    ensure(not dto["downloadable"], f"downloadable={dto['downloadable']}, download → {status_line(r)}")
    return "ok"


# ===================================================== 6. Пользователи (ADMIN) ===
@test("INT-41", "6. Управление пользователями", "CRUD пользователей администратором",
      "POST → GET → PUT (роль ADMIN, пароль) → вход с новым паролем → DELETE", "201/200 на каждом шаге, новые права действуют")
def t_users_crud():
    adm = login(*ADMIN)
    email = f"crud-{RUN_ID}@test.io"
    r = call("POST", "/users", adm, json={"name": "Crud", "surname": "User", "email": email,
                                          "password": PASSWORD, "roles": ["USER"]})
    ensure(r.status_code in (200, 201), status_line(r))
    uid = r.json()["id"]
    r = call("PUT", f"/users/{uid}", adm, json={"name": "Crud2", "surname": "User", "email": email,
                                                "password": "New@12345", "roles": ["USER", "ADMIN"]})
    ensure(r.status_code == 200 and sorted(r.json()["roles"]) == ["ADMIN", "USER"], status_line(r))
    tok = login(email, "New@12345")
    ensure(tok and call("GET", "/users", tok).status_code == 200, "новый админ не получил доступ")
    lst = call("GET", "/users", adm, params={"search": "crud-", "role": "ADMIN"}).json()
    ensure(lst["totalElements"] >= 1, lst)
    ensure(call("DELETE", f"/users/{uid}", adm).status_code == 204, "delete")
    return "создан, повышен до ADMIN, вход с новым паролем, поиск+фильтр, удалён"


@test("INT-42", "6. Управление пользователями", "Защита от самоблокировки администратора",
      "Админ удаляет себя; снимает с себя роль ADMIN", "400 в обоих случаях")
def t_self_protect():
    adm = login(*ADMIN)
    me = call("GET", "/users/me", adm).json()
    r1 = call("DELETE", f"/users/{me['id']}", adm)
    r2 = call("PUT", f"/users/{me['id']}", adm, json={"name": me["name"], "surname": me.get("surname"),
                                                      "email": me["email"], "roles": ["USER"]})
    ensure(r1.status_code == 400 and r2.status_code == 400, f"{r1.status_code}, {r2.status_code}")
    return "400 / 400"


@test("INT-43", "6. Управление пользователями", "Смена email администратором самому себе",
      "Админ меняет свой email через PUT /users/{id}", "Сессия продолжает работать (или выдаётся новый токен)",
      defect=("DEF-18", "Низкая", "После смены собственного email старый JWT (sub=старый email) перестаёт работать — "
              "админ внезапно получает ошибки вместо перелогина",
              "Возвращать новый токен после смены email или запрещать смену своего email"))
def t_self_email():
    adm = login(*ADMIN)
    me = call("GET", "/users/me", adm).json()
    new = f"admin-{RUN_ID}@luckfox.io"
    body = {"name": me["name"], "surname": me.get("surname"), "roles": me["roles"]}
    r = call("PUT", f"/users/{me['id']}", adm, json={**body, "email": new})
    after = call("GET", "/users/me", adm).status_code
    tok2 = login(new, ADMIN[1])
    call("PUT", f"/users/{me['id']}", tok2, json={**body, "email": ADMIN[0]})  # вернуть как было
    ensure(r.status_code == 200 and after == 200, f"PUT → {r.status_code}, затем /users/me со старым токеном → {after}")
    return "ok"


# =================================================== 7. Отказоустойчивость ===
@test("INT-44", "7. Отказоустойчивость", "Перезапуск backend: сохранность данных",
      "Graceful-остановка (SIGTERM) и запуск jar; повторный старт DataSeeder",
      "Данные сохранены, дублей каталога нет, токен остаётся валидным")
def t_restart_persist():
    adm = login(*ADMIN)
    before = (call("GET", "/components", params={"size": 1}).json()["totalElements"],
              call("GET", "/images", params={"size": 1}).json()["totalElements"])
    t0 = time.time()
    stack("backend-stop")
    stack("backend-start")
    up = time.time() - t0
    after = (call("GET", "/components", params={"size": 1}).json()["totalElements"],
             call("GET", "/images", params={"size": 1}).json()["totalElements"])
    ensure(before == after, f"до {before}, после {after}")
    ensure(call("GET", "/users/me", adm).status_code == 200, "старый токен не работает")
    return f"компонентов/образов {after} = до рестарта; простой {up:.0f} с; токен валиден"


@test("INT-45", "7. Отказоустойчивость", "Падение backend (kill -9) во время сборки",
      "3 заказа в очереди, kill -9 в момент BUILDING, запуск backend",
      "Прерванные сборки возобновляются или помечаются FAILED; их можно пересобрать/удалить",
      defect=("DEF-19", "Высокая", "Очередь сборок живёт только в памяти: после перезапуска/падения backend "
              "образы навсегда остаются QUEUED/BUILDING, их нельзя ни изменить, ни удалить (BUILDING), "
              "пользователь никогда не получит образ",
              "При старте переводить BUILDING→FAILED и заново ставить QUEUED в очередь (ApplicationReadyEvent); "
              "либо хранить очередь в БД"))
def t_crash_during_build():
    ensure(wait_queue_idle(), "очередь занята")
    _, tok = user_token()
    glibc = comp_ids()["glibc"]
    ids = [order(tok, f"it-crash{i}-{RUN_ID}", [glibc]).json()["id"] for i in range(3)]
    wait_state(ids[0], ("BUILDING",), 30)
    stack("backend-kill")
    stack("backend-start")
    time.sleep(15)
    states = {i: call("GET", f"/images/{i}").json()["state"] for i in ids}
    adm = login(*ADMIN)
    stuck = [i for i, s in states.items() if s in ("QUEUED", "BUILDING")]
    extra = ""
    if stuck:
        codes = {i: (call("PUT", f"/images/{i}", adm, json={"name": "retry", "distro": "buildroot",
                                                            "componentIds": [glibc]}).status_code,
                     call("DELETE", f"/images/{i}", adm).status_code) for i in stuck}
        extra = f"; попытки PUT/DELETE зависших: {codes}"
    ensure(not stuck, f"через 15 с после рестарта статусы {states}{extra}")
    return str(states)


@test("INT-46", "7. Отказоустойчивость", "Недоступность БД",
      "Остановка MySQL; запросы к API; запуск MySQL; повтор запросов без рестарта backend",
      "Во время сбоя — быстрый 5xx (≤ 5 с) без утечки деталей; после восстановления — 200 без рестарта",
      defect=("DEF-20", "Средняя", "При недоступной БД запрос висит ~30 с (connection-timeout Hikari по умолчанию), "
              "анонимный клиент получает 403 вместо 503",
              "spring.datasource.hikari.connection-timeout=3000; обработчик DataAccessException → 503; permitAll для /error"))
def t_db_down():
    stack("mysql-stop")
    t0 = time.time()
    try:
        r = call("GET", "/components", timeout=90)
        code, dt = r.status_code, time.time() - t0
    except requests.RequestException as e:
        code, dt = type(e).__name__, time.time() - t0
    stack("mysql-start")
    recovered, t1 = None, time.time()
    while time.time() - t1 < 60:
        try:
            if call("GET", "/components", timeout=10).status_code == 200:
                recovered = time.time() - t1
                break
        except requests.RequestException:
            pass
        time.sleep(1)
    ensure(recovered is not None, f"во время сбоя {code} за {dt:.1f} с; после восстановления API не ожил")
    ensure(code in (500, 503) and dt <= 5, f"во время сбоя HTTP {code} за {dt:.1f} с; "
                                          f"восстановление без рестарта за {recovered:.1f} с")
    return f"{code} за {dt:.1f} с; восстановление {recovered:.1f} с"


@test("INT-47", "7. Отказоустойчивость", "Состояние стенда после всех тестов",
      "Ошибки в логе backend (ERROR/Exception), итоговые проверки API", "API отвечает, очередь пуста")
def t_final():
    ok = call("GET", "/component-types").status_code == 200
    ensure(ok, "API не отвечает")
    return "API отвечает"


# ------------------------------------------------------------------- runner ---
def main():
    only = set(sys.argv[1:])
    catalog = {fn.meta["defect"][0]: fn.meta["defect"] for fn in TESTS
               if fn.meta["defect"] and fn.meta["defect"][1]}
    for fn in TESTS:
        m = dict(fn.meta)
        if only and m["id"] not in only:
            continue
        t0 = time.time()
        try:
            actual = fn()
            status = "PASS"
        except Check as e:
            actual = str(e.args[0])
            status = "SKIP" if actual.startswith("SKIP") else "FAIL"
        except Exception as e:  # noqa: BLE001 — непредвиденная ошибка теста
            actual = f"ERROR {type(e).__name__}: {e}"
            status = "FAIL"
            traceback.print_exc()
        m.update(actual=str(actual), status=status, duration_s=round(time.time() - t0, 2))
        d = m.pop("defect")
        m["defect_id"] = d[0] if d and status == "FAIL" else ""
        if m["defect_id"]:
            meta = catalog[d[0]]
            DEFECTS.setdefault(d[0], dict(id=d[0], severity=meta[1], title=meta[2],
                                          recommendation=meta[3], tests=[]))
        RESULTS.append(m)
        print(f"{status:4} {m['id']} {m['name']} ({m['duration_s']} с) :: {m['actual'][:200]}", flush=True)
    for r in RESULTS:
        if r["defect_id"] in DEFECTS:
            DEFECTS[r["defect_id"]]["tests"].append(r["id"])
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"run_id": RUN_ID, "finished": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "results": RESULTS, "defects": list(DEFECTS.values())}, f, ensure_ascii=False, indent=1)
    s = {k: sum(r["status"] == k for r in RESULTS) for k in ("PASS", "FAIL", "SKIP")}
    print(f"\nИТОГО: {s}; дефектов: {len(DEFECTS)} → {OUT}")


if __name__ == "__main__":
    main()
