'use strict';
/*
 * Anvex LSP host (F2c, P9). Runs as the single Node instance of the
 * :lsp process. Exposes a token-guarded loopback control API and runs
 * project language servers as child processes, bridging their stdio
 * to the app as base64 chunks with sequence numbers:
 *
 *   GET  /ping                     -> { ok, spawn, pid }
 *   POST /start {id, argv, cwd}    -> { ok } | { error }   (spawn mode only)
 *   POST /send  {id, dataB64}      -> { ok } | { error }
 *   GET  /poll?id=...&after=N      -> { chunks: [{seq, dataB64}], last, running, exitCode }
 *   POST /stop  {id}               -> { ok }
 *
 * Language servers are arbitrary project-local binaries/scripts, so
 * there is no WORKER fallback: when this Node cannot re-spawn itself,
 * /start refuses and the app reports LSP as unavailable (the
 * Tree-sitter features keep working).
 */
const http = require('http');
const fs = require('fs');
const { spawn } = require('child_process');

const tokenFile = process.argv[2];
const token = fs.readFileSync(tokenFile, 'utf8').trim();
const PORT = 17322;
const MAX_CHUNKS = 1024;

let spawnOk = false;
try {
  const probe = spawn(process.execPath, ['-e', 'process.exit(0)'], { stdio: 'ignore' });
  probe.on('exit', (code) => { spawnOk = code === 0; });
  probe.on('error', () => { spawnOk = false; });
} catch (e) { spawnOk = false; }

const sessions = new Map();

function getSession(id) {
  let s = sessions.get(id);
  if (!s) {
    s = { child: null, chunks: [], nextSeq: 0, running: false, exitCode: null };
    sessions.set(id, s);
  }
  return s;
}

function pushChunk(s, buf) {
  s.chunks.push({ seq: s.nextSeq++, dataB64: buf.toString('base64') });
  if (s.chunks.length > MAX_CHUNKS) s.chunks.splice(0, s.chunks.length - MAX_CHUNKS);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; });
    req.on('end', () => {
      try { resolve(JSON.parse(data || '{}')); } catch (e) { resolve({}); }
    });
  });
}

function reply(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.headers['x-anvex-token'] !== token) { reply(res, 403, { error: 'bad token' }); return; }
  const url = new URL(req.url, 'http://127.0.0.1');
  try {
    if (req.method === 'GET' && url.pathname === '/ping') {
      reply(res, 200, { ok: true, spawn: spawnOk, pid: process.pid });
    } else if (req.method === 'POST' && url.pathname === '/start') {
      const body = await readBody(req);
      const s = getSession(String(body.id || 'default'));
      if (s.running) { reply(res, 200, { ok: true, already: true }); return; }
      if (!spawnOk) { reply(res, 409, { error: 'spawn unavailable in this Node runtime' }); return; }
      if (!Array.isArray(body.argv) || body.argv.length === 0) { reply(res, 400, { error: 'argv required' }); return; }
      // node_modules/.bin entries are symlinks to the server's JS entry:
      // resolve, then run JS entries under this Node (a bare spawn of a
      // shim script would fail without a shell).
      let cmd = body.argv[0];
      try { cmd = fs.realpathSync(cmd); } catch (e) { /* keep as given */ }
      const args = body.argv.slice(1);
      const child = /\.(mjs|cjs|js)$/.test(cmd)
        ? spawn(process.execPath, [cmd].concat(args), {
            cwd: body.cwd || process.cwd(), stdio: ['pipe', 'pipe', 'pipe'],
          })
        : spawn(cmd, args, {
            cwd: body.cwd || process.cwd(), stdio: ['pipe', 'pipe', 'pipe'],
          });
      s.child = child; s.running = true; s.exitCode = null; s.chunks = []; s.nextSeq = 0;
      child.stdout.on('data', (d) => pushChunk(s, d));
      child.stderr.on('data', (d) => pushChunk(s, d));
      child.on('exit', (code) => { s.running = false; s.exitCode = code; });
      child.on('error', (err) => { s.running = false; pushChunk(s, Buffer.from(String(err))); });
      reply(res, 200, { ok: true });
    } else if (req.method === 'POST' && url.pathname === '/send') {
      const body = await readBody(req);
      const s = getSession(String(body.id || 'default'));
      if (!s.child || !s.running) { reply(res, 409, { error: 'session not running' }); return; }
      s.child.stdin.write(Buffer.from(String(body.dataB64 || ''), 'base64'));
      reply(res, 200, { ok: true });
    } else if (req.method === 'GET' && url.pathname === '/poll') {
      const s = getSession(String(url.searchParams.get('id') || 'default'));
      const after = parseInt(url.searchParams.get('after') || '-1', 10);
      const chunks = s.chunks.filter((c) => c.seq > after);
      reply(res, 200, {
        chunks,
        last: s.nextSeq - 1,
        running: s.running,
        exitCode: s.exitCode,
      });
    } else if (req.method === 'POST' && url.pathname === '/stop') {
      const body = await readBody(req);
      const s = getSession(String(body.id || 'default'));
      if (s.child) { try { s.child.kill(); } catch (e) { /* already gone */ } }
      s.running = false;
      sessions.delete(String(body.id || 'default'));
      reply(res, 200, { ok: true });
    } else {
      reply(res, 404, { error: 'unknown route' });
    }
  } catch (e) {
    reply(res, 500, { error: String(e) });
  }
});

server.listen(PORT, '127.0.0.1');
