// Минимальный статический сервер для собранного SPA (dist/) без внешних зависимостей.
// Используется в Docker-образе, где нельзя ставить пакеты из сети (нет DNS в build).
// Отдаёт файлы из dist, для неизвестных путей — index.html (клиентский роутинг).
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { join, extname, normalize } from 'node:path';

const ROOT = join(process.cwd(), 'dist');
const PORT = process.env.PORT || 5173;

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
};

async function send(res, filePath, status = 200) {
  const body = await readFile(filePath);
  res.writeHead(status, { 'Content-Type': TYPES[extname(filePath)] || 'application/octet-stream' });
  res.end(body);
}

const server = createServer(async (req, res) => {
  try {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    let filePath = normalize(join(ROOT, urlPath));
    if (!filePath.startsWith(ROOT)) { res.writeHead(403); return res.end('Forbidden'); }

    try {
      const s = await stat(filePath);
      if (s.isDirectory()) filePath = join(filePath, 'index.html');
      await send(res, filePath);
    } catch {
      // SPA fallback
      await send(res, join(ROOT, 'index.html'));
    }
  } catch (e) {
    res.writeHead(500);
    res.end('Server error');
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[frontend] serving dist/ on http://0.0.0.0:${PORT}`);
});
