// Нагрузочные сценарии Luckfox Image Builder (k6).
//
//   k6 run -e PROFILE=<профиль> -e API_URL=http://localhost:8080/api -e WEB_URL=http://localhost:5173 scenarios.js
//
// Профили (PROFILE):
//   smoke    — 1 пользователь, все пользовательские пути: базовые времена отклика
//   browse   — анонимный просмотр каталогов, 0→50 VU
//   mixed    — реалистичный продакшен-профиль: гости, пользователи, заказчики сборок, админы (до 100 VU)
//   stress   — только чтение без пауз, 50→600 итераций/с ступенями: поиск точки насыщения
//   breakpoint — продолжение стресса: 1000→4000 итераций/с до отказа
//   spike    — всплеск 10 → 400 VU за 10 с и возврат
//   login    — шторм входов (BCrypt), 5→100 входов/с
//   burst    — 60 одновременных заказов сборки: очередь, время до READY
//   download — 20 пользователей непрерывно скачивают .img
//   soak     — 40 VU смешанной нагрузки 15 минут: утечки/деградация
//   volume   — чтение каталогов при объёме БД ×100 (фиксированные 40 запросов/с)
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const API = __ENV.API_URL || 'http://localhost:8080/api';
const WEB = __ENV.WEB_URL || 'http://localhost:5173';
const PROFILE = __ENV.PROFILE || 'smoke';
const PASSWORD = 'Load@1234';

const timeToReady = new Trend('build_time_to_ready', true);
const buildsDone = new Counter('builds_ready');
const buildsFailed = new Counter('builds_failed');

// ------------------------------------------------------------- профили ---
const READ_SLA = { 'http_req_failed': ['rate<0.01'], 'http_req_duration{kind:read}': ['p(95)<500'] };
const PROFILES = {
  smoke: {
    scenarios: { smoke: { executor: 'constant-vus', vus: 1, duration: '1m', exec: 'smokeFlow' } },
    thresholds: { 'http_req_failed': ['rate<0.01'], 'http_req_duration': ['p(95)<300'] },
  },
  browse: {
    scenarios: {
      guests: { executor: 'ramping-vus', exec: 'guest', startVUs: 0,
        stages: [{ duration: '1m', target: 50 }, { duration: '3m', target: 50 }, { duration: '30s', target: 0 }] },
    },
    thresholds: READ_SLA,
  },
  mixed: {
    scenarios: {
      guests: { executor: 'ramping-vus', exec: 'guest', startVUs: 0,
        stages: [{ duration: '1m', target: 70 }, { duration: '5m', target: 70 }, { duration: '30s', target: 0 }] },
      users: { executor: 'ramping-vus', exec: 'member', startVUs: 0,
        stages: [{ duration: '1m', target: 20 }, { duration: '5m', target: 20 }, { duration: '30s', target: 0 }] },
      builders: { executor: 'ramping-vus', exec: 'builder', startVUs: 0,
        stages: [{ duration: '1m', target: 8 }, { duration: '5m', target: 8 }, { duration: '30s', target: 0 }] },
      admins: { executor: 'constant-vus', exec: 'admin', vus: 2, duration: '6m30s' },
    },
    thresholds: { ...READ_SLA, 'http_req_duration{kind:write}': ['p(95)<1000'] },
  },
  stress: {
    scenarios: {
      reads: { executor: 'ramping-arrival-rate', exec: 'readOnce', startRate: 25, timeUnit: '1s',
        preAllocatedVUs: 100, maxVUs: 1500,
        stages: [
          { duration: '30s', target: 50 }, { duration: '1m', target: 50 },
          { duration: '30s', target: 100 }, { duration: '1m', target: 100 },
          { duration: '30s', target: 200 }, { duration: '1m', target: 200 },
          { duration: '30s', target: 300 }, { duration: '1m', target: 300 },
          { duration: '30s', target: 450 }, { duration: '1m', target: 450 },
          { duration: '30s', target: 600 }, { duration: '1m', target: 600 },
        ] },
    },
    thresholds: READ_SLA,
  },
  breakpoint: {
    scenarios: {
      reads: { executor: 'ramping-arrival-rate', exec: 'readOnce', startRate: 600, timeUnit: '1s',
        preAllocatedVUs: 400, maxVUs: 3000,
        stages: [
          { duration: '30s', target: 1000 }, { duration: '1m', target: 1000 },
          { duration: '30s', target: 1500 }, { duration: '1m', target: 1500 },
          { duration: '30s', target: 2000 }, { duration: '1m', target: 2000 },
          { duration: '30s', target: 3000 }, { duration: '1m', target: 3000 },
          { duration: '30s', target: 4000 }, { duration: '1m', target: 4000 },
        ] },
    },
    thresholds: READ_SLA,
  },
  spike: {
    scenarios: {
      guests: { executor: 'ramping-vus', exec: 'guestFast', startVUs: 10,
        stages: [{ duration: '1m', target: 10 }, { duration: '10s', target: 400 }, { duration: '1m', target: 400 },
                 { duration: '10s', target: 10 }, { duration: '1m', target: 10 }] },
    },
    thresholds: READ_SLA,
  },
  login: {
    scenarios: {
      logins: { executor: 'ramping-arrival-rate', exec: 'loginOnce', startRate: 5, timeUnit: '1s',
        preAllocatedVUs: 50, maxVUs: 600,
        stages: [{ duration: '30s', target: 10 }, { duration: '30s', target: 10 },
                 { duration: '30s', target: 25 }, { duration: '30s', target: 25 },
                 { duration: '30s', target: 50 }, { duration: '30s', target: 50 },
                 { duration: '30s', target: 100 }, { duration: '30s', target: 100 }] },
    },
    thresholds: { 'http_req_failed': ['rate<0.01'], 'http_req_duration{name:auth_login}': ['p(95)<1000'] },
  },
  burst: {
    scenarios: { orders: { executor: 'per-vu-iterations', exec: 'burstOrder', vus: 60, iterations: 1, maxDuration: '20m' } },
    thresholds: { 'http_req_failed': ['rate<0.01'], 'http_req_duration{name:build_order}': ['p(95)<1000'] },
  },
  download: {
    scenarios: { downloads: { executor: 'constant-vus', exec: 'downloader', vus: 20, duration: '2m' } },
    thresholds: { 'http_req_failed': ['rate<0.01'], 'http_req_duration{name:download}': ['p(95)<3000'] },
  },
  soak: {
    scenarios: {
      guests: { executor: 'constant-vus', exec: 'guest', vus: 28, duration: '15m' },
      users: { executor: 'constant-vus', exec: 'member', vus: 8, duration: '15m' },
      builders: { executor: 'constant-vus', exec: 'builder', vus: 3, duration: '15m' },
      admins: { executor: 'constant-vus', exec: 'admin', vus: 1, duration: '15m' },
    },
    thresholds: { ...READ_SLA, 'http_req_duration{kind:write}': ['p(95)<1000'] },
  },
  volume: {
    scenarios: {
      reads: { executor: 'constant-arrival-rate', exec: 'readOnce', rate: 40, timeUnit: '1s', duration: '3m',
        preAllocatedVUs: 50, maxVUs: 800 },
    },
    thresholds: READ_SLA,
  },
};

export const options = {
  ...PROFILES[PROFILE],
  setupTimeout: '5m',
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'k6-luckfox-load',
};

// --------------------------------------------------------------- helpers ---
const JSON_H = { 'Content-Type': 'application/json' };
const auth = (t) => ({ ...JSON_H, Authorization: `Bearer ${t}` });
const pick = (a) => a[Math.floor(Math.random() * a.length)];
const think = (min, max) => sleep(min + Math.random() * (max - min));
const qs = (o) => Object.keys(o).filter((k) => o[k] !== '' && o[k] !== undefined)
  .map((k) => `${k}=${encodeURIComponent(o[k])}`).join('&');

function get(path, name, token, kind = 'read') {
  const r = http.get(`${API}${path}`, { headers: token ? auth(token) : JSON_H, tags: { name, kind } });
  check(r, { [`${name} 200`]: (x) => x.status === 200 });
  return r;
}

function post(path, body, name, token, kind = 'write', expect = [200, 201, 202]) {
  const r = http.post(`${API}${path}`, JSON.stringify(body), { headers: token ? auth(token) : JSON_H, tags: { name, kind } });
  check(r, { [`${name} ok`]: (x) => expect.includes(x.status) });
  return r;
}

function login(email, password) {
  const r = post('/auth/login', { email, password }, 'auth_login', null, 'auth', [200]);
  return r.status === 200 ? r.json('token') : null;
}

// ------------------------------------------------------------------ setup ---
export function setup() {
  const adminToken = login('admin@luckfox.io', 'Admin@123');
  const comps = http.get(`${API}/components?size=100`).json('content');
  const real = comps.filter((c) => !c.name.startsWith('Dummy') && !c.name.startsWith('vol-')
    && !c.name.startsWith('it-') && !c.name.startsWith('load-')).map((c) => c.id);
  const ready = http.get(`${API}/images?state=READY&size=100`).json('content').map((i) => i.id);
  const needUsers = { mixed: 30, soak: 12, burst: 60, download: 20, login: 50, smoke: 1 }[PROFILE] || 0;
  const users = [];
  const run = Date.now().toString(36);
  for (let i = 0; i < needUsers; i++) {
    const email = `load-${PROFILE}-${run}-${i}@test.io`;
    const r = http.post(`${API}/auth/register`, JSON.stringify({ name: 'Load', surname: `U${i}`, email, password: PASSWORD }),
      { headers: JSON_H, tags: { name: 'setup_register', kind: 'setup' } });
    if (r.status === 200) users.push({ email, token: r.json('token') });
  }
  return { adminToken, real, ready, users, allComps: comps.map((c) => c.id) };
}

// --------------------------------------------------------- building blocks ---
function openSite() {
  const r = http.get(`${WEB}/library/images`, { tags: { name: 'web_page', kind: 'web' } });
  check(r, { 'web_page 200': (x) => x.status === 200 });
  const assets = (r.body || '').match(/\/assets\/[^"]+/g) || [];
  if (assets.length) {
    http.batch(assets.map((a) => ['GET', `${WEB}${a}`, null, { tags: { name: 'web_asset', kind: 'web' } }]));
  }
}

function browseImages(d) {
  const params = {
    search: pick(['', '', '', 'cv', 'dummy', 'box', 'iot']),
    state: pick(['', '', 'READY']),
    sort: pick(['', '', 'price_asc', 'price_desc']),
    page: pick([0, 0, 0, 1, 2]),
    size: 12,
  };
  let name = 'images_list';
  if (Math.random() < 0.3 && d.real.length) {
    params.components = [pick(d.real), pick(d.real)].join(',');
    name = 'images_filter';
  }
  const r = get(`/images?${qs(params)}`, name);
  const items = r.status === 200 ? r.json('content') : [];
  if (items && items.length && Math.random() < 0.4) get(`/images/${pick(items).id}`, 'images_get');
}

function browseComponents() {
  const params = {
    search: pick(['', '', 'py', 'lib', 'dummy1', 'ssh']),
    type: pick(['', '', 1, 2]),
    sort: pick(['', 'price_asc', 'price_desc']),
    page: pick([0, 0, 1]),
    size: 12,
  };
  const r = get(`/components?${qs(params)}`, 'components_list');
  const items = r.status === 200 ? r.json('content') : [];
  if (items && items.length && Math.random() < 0.3) get(`/components/${pick(items).id}`, 'components_get');
}

// -------------------------------------------------------------- сценарии ---
// Гость: заходит на сайт, листает библиотеки с паузами «на чтение».
export function guest(d) {
  if (__ITER === 0) openSite();
  group('guest', () => {
    browseImages(d);
    think(1, 3);
    if (Math.random() < 0.5) { browseComponents(); think(1, 3); }
    if (Math.random() < 0.2) get('/component-types', 'types');
  });
}

// Гость для всплеска — короче паузы.
export function guestFast(d) {
  if (__ITER === 0) openSite();
  browseImages(d);
  if (Math.random() < 0.5) browseComponents();
  think(0.5, 1.5);
}

// Одна операция чтения без пауз (для профилей с заданной интенсивностью).
export function readOnce(d) {
  const x = Math.random();
  if (x < 0.55) browseImages(d);
  else if (x < 0.9) browseComponents();
  else if (x < 0.95) get('/component-types', 'types');
  else {
    const r = http.get(`${WEB}/library/images`, { tags: { name: 'web_page', kind: 'web' } });
    check(r, { 'web_page 200': (y) => y.status === 200 });
  }
}

// Зарегистрированный пользователь: вход, кабинет, библиотеки.
export function member(d) {
  const u = d.users[(__VU - 1) % d.users.length];
  if (__ITER === 0 || !u.session) u.session = login(u.email, PASSWORD);
  const t = u.session;
  get('/users/me', 'me', t);
  get('/users/me/images', 'my_images', t);
  think(1, 3);
  browseImages(d);
  think(1, 3);
  browseComponents();
  think(2, 5);
}

function waitReady(id, token, maxSec) {
  const t0 = Date.now();
  while ((Date.now() - t0) / 1000 < maxSec) {
    const r = http.get(`${API}/images/${id}`, { headers: auth(token), tags: { name: 'image_poll', kind: 'read' } });
    const st = r.status === 200 ? r.json('state') : '';
    if (st === 'READY') { timeToReady.add(Date.now() - t0); buildsDone.add(1); return true; }
    if (st === 'FAILED') { buildsFailed.add(1); return false; }
    sleep(2);
  }
  buildsFailed.add(1);
  return false;
}

// Заказчик: собирает образ в редакторе, ждёт готовности, скачивает.
export function builder(d) {
  const u = d.users[(__VU - 1 + 20) % d.users.length];
  if (!u.session) u.session = login(u.email, PASSWORD);
  const t = u.session;
  get(`/components?size=10&search=${pick(['', 'py', 'ssh'])}`, 'components_list');
  think(2, 5);
  const comps = [pick(d.real), pick(d.real)];
  const r = post('/images/build', { name: `load-${__VU}-${__ITER}`, description: 'load test', distro: 'buildroot',
    board: pick(['RV1103_Luckfox_Pico', 'RV1106_Luckfox_Pico_Pro_Max']), componentIds: comps }, 'build_order', t);
  if (r.status === 202 && waitReady(r.json('id'), t, 600)) {
    const dl = http.get(`${API}/images/${r.json('id')}/download`, { headers: auth(t), tags: { name: 'download', kind: 'download' } });
    check(dl, { 'download 200': (x) => x.status === 200 });
  }
  think(5, 10);
}

// Администратор: ведёт каталог компонентов и смотрит пользователей.
export function admin(d) {
  const t = d.adminToken;
  get(`/users?${qs({ search: pick(['', 'load', 'it-']), page: 0, size: 20 })}`, 'admin_users', t);
  think(1, 3);
  const c = post('/components', { name: `load-comp-${__VU}-${__ITER}-${Date.now() % 100000}`, typeId: pick([1, 2]),
    cost: 5, description: 'load', dependencyIds: [pick(d.real)] }, 'admin_comp_create', t);
  if (c.status === 200 || c.status === 201) {
    const id = c.json('id');
    think(1, 3);
    const u = http.put(`${API}/components/${id}`, JSON.stringify({ name: c.json('name'), typeId: 1, cost: 7, dependencyIds: [] }),
      { headers: auth(t), tags: { name: 'admin_comp_update', kind: 'write' } });
    check(u, { 'admin_comp_update ok': (x) => x.status === 200 });
    think(1, 3);
    const del = http.del(`${API}/components/${id}`, null, { headers: auth(t), tags: { name: 'admin_comp_delete', kind: 'write' } });
    check(del, { 'admin_comp_delete ok': (x) => x.status === 204 });
  }
  think(3, 6);
}

export function loginOnce(d) {
  const u = pick(d.users);
  login(u.email, PASSWORD);
}

export function burstOrder(d) {
  const u = d.users[(__VU - 1) % d.users.length];
  const r = post('/images/build', { name: `load-burst-${__VU}`, description: 'burst', distro: 'buildroot',
    board: 'RV1103_Luckfox_Pico', componentIds: [pick(d.real)] }, 'build_order', u.token);
  if (r.status === 202) waitReady(r.json('id'), u.token, 1100);
}

export function downloader(d) {
  const u = d.users[(__VU - 1) % d.users.length];
  const r = http.get(`${API}/images/${pick(d.ready)}/download`,
    { headers: auth(u.token), tags: { name: 'download', kind: 'download' }, responseType: 'none' });
  check(r, { 'download 200': (x) => x.status === 200 });
  think(0.5, 1.5);
}

// Дымовой проход всех пользовательских путей одним пользователем.
export function smokeFlow(d) {
  openSite();
  browseImages(d);
  browseComponents();
  get('/component-types', 'types');
  const u = d.users[0];
  const t = login(u.email, PASSWORD);
  get('/users/me', 'me', t);
  get('/users/me/images', 'my_images', t);
  get(`/users?size=20`, 'admin_users', d.adminToken);
  if (__ITER % 5 === 0) {
    const r = post('/images/build', { name: `load-smoke-${__ITER}`, distro: 'buildroot', board: 'RV1103_Luckfox_Pico',
      componentIds: [pick(d.real)] }, 'build_order', t);
    if (r.status === 202 && waitReady(r.json('id'), t, 60)) {
      const dl = http.get(`${API}/images/${r.json('id')}/download`, { headers: auth(t), tags: { name: 'download', kind: 'download' } });
      check(dl, { 'download 200': (x) => x.status === 200 });
    }
  }
  sleep(1);
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY || `summary-${PROFILE}.json`]: JSON.stringify(data, null, 1) };
}
