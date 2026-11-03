'use strict';
/*
 * Anvex dev-server host (F5a, P5). Runs as the single Node instance of
 * the :devserver process. Exposes a token-guarded loopback control API
 * and runs project dev servers:
 *
 *  - SPAWN mode (when this Node's execPath can re-spawn itself, probed
 *    at boot): full shell commands with node/npm/npx shims, so
 *    `npm run dev` and friends work as on a desktop.
 *  - WORKER mode (fallback): a plain `node <file>.js` entry runs in a
 *    worker thread; anything else is refused with the reason.
 *
 * Control API (header `x-anvex-token` required):
 *   GET  /ping            -> { ok, spawn, pid }
 *   POST /start {id, cwd, script, env} -> { ok } | { error }
 *   GET  /status?id=...   -> { running, url, mode, exitCode, lines }
 *   POST /stop  {id}      -> { ok }
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { Worker } = require('worker_threads');

const tokenFile = process.argv[2];
const runtimeDir = process.argv[3] || '';
const token = fs.readFileSync(tokenFile, 'utf8').trim();
const PORT = 17321;
const npmCli = path.join(runtimeDir, 'npm', 'bin', 'npm-cli.js');
const npxCli = path.join(runtimeDir, 'npm', 'bin', 'npx-cli.js');

let spawnOk = false;
try {
  const probe = spawn(process.execPath, ['-e', 'process.exit(0)'], { stdio: 'ignore' });
  probe.on('exit', (code) => { spawnOk = code === 0; });
  probe.on('error', () => { spawnOk = false; });
} catch (e) { spawnOk = false; }

const servers = new Map();

function pushLine(s, line) {
  const text = String(line).replace(/\x1b\[[0-9;]*m/g, '');
  s.lines.push(text);
  if (s.lines.length > 300) s.lines.shift();
  if (!s.url) detectUrl(s, text);
}

function detectUrl(s, line) {
  let m = line.match(/https?:\/\/(?:127\.0\.0\.1|localhost|\[::1\]):(\d+)[^\s]*/);
  if (m) { s.url = m[0].replace('localhost', '127.0.0.1').replace('[::1]', '127.0.0.1'); return; }
  m = line.match(/(?:listening on|bound to|running at|started server on)(?: port)?\s*:?\s*(\d{2,5})/i);
  if (m) { s.url = 'http://127.0.0.1:' + m[1]; return; }
  m = line.match(/port\s+(\d{2,5})/i);
  if (m && /listen|ready|start/i.test(line)) { s.url = 'http://127.0.0.1:' + m[1]; }
}

function wireStream(s, stream) {
  let buf = '';
  stream.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      pushLine(s, buf.slice(0, idx));
      buf = buf.slice(idx + 1);
    }
  });
  stream.on('end', () => { if (buf) pushLine(s, buf); });
}

function writeShims(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const q = (p) => '"' + p.replace(/"/g, '') + '"';
  const nodeShim = '#!/system/bin/sh\nexec ' + q(process.execPath) + ' "$@"\n';
  fs.writeFileSync(path.join(dir, 'node'), nodeShim, { mode: 0o755 });
  if (fs.existsSync(npmCli)) {
    fs.writeFileSync(path.join(dir, 'npm'),
      '#!/system/bin/sh\nexec ' + q(process.execPath) + ' ' + q(npmCli) + ' "$@"\n', { mode: 0o755 });
  }
  if (fs.existsSync(npxCli)) {
    fs.writeFileSync(path.join(dir, 'npx'),
      '#!/system/bin/sh\nexec ' + q(process.execPath) + ' ' + q(npxCli) + ' "$@"\n', { mode: 0o755 });
  }
}

function startServer(id, cwd, script, extraEnv) {
  if (servers.has(id)) return { error: 'already running' };
  if (!fs.existsSync(cwd)) return { error: 'working directory not found' };
  const s = { lines: [], url: null, mode: null, exitCode: null, child: null, worker: null };
  const env = Object.assign({}, process.env, extraEnv || {});
  if (spawnOk) {
    const shimDir = path.join(runtimeDir, 'devserver-shims');
    try { writeShims(shimDir); } catch (e) { /* shims best-effort */ }
    env.PATH = shimDir + ':' + (env.PATH || '/system/bin');
    const child = spawn('/system/bin/sh', ['-c', script], { cwd, env });
    s.mode = 'spawn';
    s.child = child;
    wireStream(s, child.stdout);
    wireStream(s, child.stderr);
    child.on('exit', (code) => { s.exitCode = code == null ? -1 : code; pushLine(s, '[devserver] exited with code ' + s.exitCode); });
    child.on('error', (err) => { s.exitCode = -1; pushLine(s, '[devserver] spawn error: ' + err.message); });
  } else {
    const m = script.match(/^\s*node\s+(\S+\.m?js)\s*(.*)$/);
    if (!m) {
      return { error: 'This device cannot spawn shell commands from the Node host, so only plain "node <file>.js" dev servers are supported. Command was: ' + script };
    }
    const entry = path.isAbsolute(m[1]) ? m[1] : path.join(cwd, m[1]);
    if (!fs.existsSync(entry)) return { error: 'entry not found: ' + m[1] };
    const worker = new Worker(entry, {
      stdout: true, stderr: true,
      env: Object.assign({}, env, { ANVEX_CWD: cwd }),
      argv: m[2] ? m[2].split(/\s+/).filter(Boolean) : [],
    });
    s.mode = 'worker';
    s.worker = worker;
    wireStream(s, worker.stdout);
    wireStream(s, worker.stderr);
    worker.on('exit', (code) => { s.exitCode = code; pushLine(s, '[devserver] exited with code ' + code); });
    worker.on('error', (err) => { pushLine(s, '[devserver] worker error: ' + err.message); });
    // Worker threads share the host cwd; note the entry runs with the
    // host's cwd, so relative file access resolves against ANVEX_CWD
    // only if the script honors it — surfaced honestly in the docs.
    pushLine(s, '[devserver] worker mode: entry ' + m[1] + ' (host cwd; ANVEX_CWD=' + cwd + ')');
  }
  servers.set(id, s);
  return { ok: true, mode: s.mode };
}

function stopServer(id) {
  const s = servers.get(id);
  if (!s) return { ok: true };
  try { if (s.child) s.child.kill('SIGTERM'); } catch (e) {}
  try { if (s.worker) s.worker.terminate(); } catch (e) {}
  servers.delete(id);
  return { ok: true };
}

const server = http.createServer((req, res) => {
  if (req.headers['x-anvex-token'] !== token) {
    res.writeHead(403, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'forbidden' }));
    return;
  }
  const url = new URL(req.url, 'http://127.0.0.1');
  const send = (obj) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj));
  };
  if (req.method === 'GET' && url.pathname === '/ping') {
    send({ ok: true, spawn: spawnOk, pid: process.pid });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/status') {
    const s = servers.get(url.searchParams.get('id') || '');
    if (!s) { send({ running: false, gone: true }); return; }
    send({
      running: s.exitCode === null,
      url: s.url, mode: s.mode, exitCode: s.exitCode,
      lines: s.lines.slice(-60),
    });
    return;
  }
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    let json = {};
    try { json = JSON.parse(body || '{}'); } catch (e) { /* empty */ }
    if (req.method === 'POST' && url.pathname === '/start') {
      send(startServer(String(json.id || 'dev'), String(json.cwd || ''), String(json.script || ''), json.env || {}));
    } else if (req.method === 'POST' && url.pathname === '/stop') {
      send(stopServer(String(json.id || 'dev')));
    } else {
      res.writeHead(404, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'not found' }));
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('[devserver-host] listening on 127.0.0.1:' + PORT + ' spawn=' + spawnOk);
});
