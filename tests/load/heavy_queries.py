"""Замер «тяжёлых» запросов каталога без конкуренции: задержка (30 повторов) и число SQL на запрос.

  python3 heavy_queries.py <out.json> <label>
Используется в объёмном тесте: прогон до и после наполнения БД (volume_seed.sql).
MYSQL_CLI — команда mysql-клиента (счётчик Questions).
"""
import json
import os
import shlex
import subprocess
import sys
import time

import requests

API = os.environ.get("API_URL", "http://localhost:8080/api")
MYSQL_CLI = os.environ.get("MYSQL_CLI")
N = 30


def questions():
    out = subprocess.run(shlex.split(MYSQL_CLI) + ["-N", "-B", "-e", "SHOW GLOBAL STATUS LIKE 'Questions'"],
                         capture_output=True, text=True).stdout.split()
    return int(out[1])


def main():
    out, label = sys.argv[1], sys.argv[2]
    ids = {c["name"]: c["id"] for c in requests.get(f"{API}/components", params={"size": 100}).json()["content"]}
    tok = requests.post(f"{API}/auth/login", json={"email": "admin@luckfox.io", "password": "Admin@123"}).json()["token"]
    admin = {"Authorization": f"Bearer {tok}"}
    cases = [
        ("Образы: первая страница (12)", "/images", {"size": 12}, None),
        ("Образы: страница 100 макс. размера", "/images", {"size": 100}, None),
        ("Образы: глубокая страница (page=1500)", "/images", {"page": 1500, "size": 12}, None),
        ("Образы: поиск по подстроке", "/images", {"search": "image-199", "size": 12}, None),
        ("Образы: фильтр по 2 компонентам", "/images", {"components": f"{ids['python3']},{ids['glibc']}", "size": 12}, None),
        ("Образы: сортировка по цене ↓ + статус", "/images", {"sort": "price_desc", "state": "READY", "size": 12}, None),
        ("Компоненты: первая страница", "/components", {"size": 12}, None),
        ("Компоненты: поиск + тип + цена ↑", "/components", {"search": "comp-1", "type": 1, "sort": "price_asc", "size": 12}, None),
        ("Пользователи (ADMIN): поиск", "/users", {"search": "user-4", "size": 20}, admin),
    ]
    res = []
    for name, path, params, headers in cases:
        requests.get(API + path, params=params, headers=headers)  # прогрев
        q0 = questions()
        requests.get(API + path, params=params, headers=headers)
        sql = questions() - q0 - 1
        lat = []
        for _ in range(N):
            t = time.perf_counter()
            r = requests.get(API + path, params=params, headers=headers)
            lat.append((time.perf_counter() - t) * 1000)
        lat.sort()
        res.append(dict(name=name, path=path, params=params, status=r.status_code,
                        total=r.json().get("totalElements"), sql=sql, bytes=len(r.content),
                        med=round(lat[N // 2], 1), p95=round(lat[int(N * 0.95) - 1], 1), max=round(lat[-1], 1)))
        print(f"{label:6} {name:42} total={res[-1]['total']:>6} sql={sql:>4} med={res[-1]['med']:>7} p95={res[-1]['p95']:>7}")
    data = json.load(open(out)) if os.path.exists(out) else {}
    data[label] = res
    json.dump(data, open(out, "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
