/*
 * Anvex Node-compatibility layer prelude (v4.4).
 *
 * Runs inside the WebView JS engine. This is a deliberately small, honest
 * subset of Node — see NodeCompatRuntime.kt for the exact contract:
 *   console.*, setTimeout passthrough, process.{argv,env,version,platform,exit},
 *   fs sync subset, require() for relative project files only (no node_modules).
 * No network. No Buffer. No child_process. No npm.
 */
var __AnvexNodeState = { exitCode: 0, started: Date.now() };

var console = {
  log: function () { AnvexNodeConsole.log('log', __fmt(arguments)); },
  info: function () { AnvexNodeConsole.log('info', __fmt(arguments)); },
  warn: function () { AnvexNodeConsole.log('warn', __fmt(arguments)); },
  error: function () { AnvexNodeConsole.log('error', __fmt(arguments)); },
  debug: function () { AnvexNodeConsole.log('debug', __fmt(arguments)); }
};

function __fmt(args) {
  var parts = [];
  for (var i = 0; i < args.length; i++) {
    var v = args[i];
    try {
      if (typeof v === 'string') parts.push(v);
      else if (v instanceof Error) parts.push(v.stack || (v.name + ': ' + v.message));
      else parts.push(JSON.stringify(v, __jsonSafe(), 2));
    } catch (e) { parts.push(String(v)); }
  }
  return parts.join(' ');
}
function __jsonSafe() {
  var seen = [];
  return function (k, val) {
    if (typeof val === 'object' && val !== null) {
      if (seen.indexOf(val) !== -1) return '[Circular]';
      seen.push(val);
    }
    if (typeof val === 'function') return '[Function ' + (val.name || 'anonymous') + ']';
    return val;
  };
}

var process = {
  argv: ['node', __SCRIPT_PATH__],
  env: {},
  version: 'v4.4-anvex-compat',
  platform: 'android',
  hrtime: function () { var t = Date.now() / 1000; return [Math.floor(t), Math.floor((t % 1) * 1e9)]; },
  exit: function (code) {
    __AnvexNodeState.exitCode = (typeof code === 'number') ? code : 0;
    throw { __anvexProcessExit: true, code: __AnvexNodeState.exitCode };
  }
};

// ---- fs (workspace-sandboxed; the Java bridge re-sanitizes every path) ----
function __fsOk(r) { var o = JSON.parse(r); if (!o.ok) throw new Error('fs: ' + (o.error || 'failed')); return o.data; }
var fs = {
  readFileSync: function (p, enc) {
    var data = __fsOk(AnvexNodeFs.invoke('read', String(p), null));
    return (enc === undefined || enc === null || enc === 'utf8' || enc === 'utf-8') ? data
      : __toBufferLike(data);
  },
  writeFileSync: function (p, data) {
    if (data && typeof data === 'object' && data.__anvexBytes) data = __fromBufferLike(data);
    __fsOk(AnvexNodeFs.invoke('write', String(p), String(data)));
  },
  appendFileSync: function (p, data) {
    if (data && typeof data === 'object' && data.__anvexBytes) data = __fromBufferLike(data);
    __fsOk(AnvexNodeFs.invoke('append', String(p), String(data)));
  },
  existsSync: function (p) { return __fsOk(AnvexNodeFs.invoke('exists', String(p), null)); },
  readdirSync: function (p) { return __fsOk(AnvexNodeFs.invoke('readdir', String(p) || '.', null)); },
  mkdirSync: function (p) { __fsOk(AnvexNodeFs.invoke('mkdir', String(p), null)); },
  statSync: function (p) {
    var s = __fsOk(AnvexNodeFs.invoke('stat', String(p), null));
    return {
      size: s.size, isDirectory: function () { return s.isDirectory; },
      isFile: function () { return s.isFile; }, mtime: new Date()
    };
  },
  unlinkSync: function (p) { __fsOk(AnvexNodeFs.invoke('unlink', String(p), null)); }
};
// Minimal Buffer stand-in: utf8 strings only, honest about its limits.
function __toBufferLike(text) { return { __anvexBytes: true, length: text.length, toString: function () { return text; } }; }
function __fromBufferLike(b) { return (b && typeof b.toString === 'function') ? b.toString() : String(b); }

// ---- require(): relative project files only --------------------------------
var __moduleCache = {};
var __requireDepth = 0;
function require(spec) {
  if (typeof spec !== 'string') throw new Error('require() needs a string');
  if (spec.charAt(0) !== '.') throw new Error(
    'require("' + spec + '") is outside the compat layer: only relative project files (./ ../) are supported. ' +
    'npm packages need the Termux backend (Settings > Workspace & build).');
  var resolved = __resolveRelativeTo(__dirname(), spec);
  var key = resolved.path;
  if (Object.prototype.hasOwnProperty.call(__moduleCache, key)) return __moduleCache[key].exports;
  if (++__requireDepth > 20) { __requireDepth--; throw new Error('require chain too deep (limit 20)'); }
  var mod = { exports: {}, filename: key, id: key };
  __moduleCache[key] = mod;
  try {
    var fn = new Function('require', 'module', 'exports', '__filename', '__dirname', 'process', 'console', 'fs',
      resolved.source);
    fn(function (s) { return requireFrom(key, s); }, mod, mod.exports, key, __dirOf(key), process, console, fs);
  } finally { __requireDepth--; }
  return mod.exports;
}
function requireFrom(fromFile, spec) {
  if (typeof spec !== 'string') throw new Error('require() needs a string');
  if (spec.charAt(0) !== '.') throw new Error('require("' + spec + '") is outside the compat layer (relative files only).');
  var base = __dirOf(fromFile);
  var resolved = __resolveRelativeTo(base, spec);
  var key = resolved.path;
  if (Object.prototype.hasOwnProperty.call(__moduleCache, key)) return __moduleCache[key].exports;
  if (++__requireDepth > 20) { __requireDepth--; throw new Error('require chain too deep (limit 20)'); }
  var mod = { exports: {}, filename: key, id: key };
  __moduleCache[key] = mod;
  try {
    var fn = new Function('require', 'module', 'exports', '__filename', '__dirname', 'process', 'console', 'fs',
      resolved.source);
    fn(function (s) { return requireFrom(key, s); }, mod, mod.exports, key, __dirOf(key), process, console, fs);
  } finally { __requireDepth--; }
  return mod.exports;
}
function __resolveRelativeTo(baseDir, spec) {
  var joined = baseDir ? baseDir + '/' + spec : spec;
  var normalized = [];
  joined.split('/').forEach(function (part) {
    if (!part || part === '.') return;
    if (part === '..') { if (normalized.length === 0) throw new Error('require escapes the workspace'); normalized.pop(); }
    else normalized.push(part);
  });
  var path = normalized.join('/');
  var tryPaths = [path, path + '.js', path + '.json', path + '/index.js'];
  for (var i = 0; i < tryPaths.length; i++) {
    var r = AnvexNodeFs.invoke('read', tryPaths[i], null);
    var o = JSON.parse(r);
    if (o.ok) return { path: tryPaths[i], source: o.data };
  }
  throw new Error("Cannot find module '" + spec + "' (relative project files only)");
}
function __dirname() { return __dirOf(__SCRIPT_PATH__); }
function __dirOf(file) { var i = String(file).lastIndexOf('/'); return i <= 0 ? '' : String(file).slice(0, i); }

// ---- module runner ----------------------------------------------------------
var AnvexNode = {
  __run: function (scriptPath, source) {
    __SCRIPT_PATH__ = scriptPath;
    process.argv[1] = scriptPath;
    var mod = { exports: {} };
    __moduleCache[scriptPath] = mod;
    try {
      var fn = new Function('require', 'module', 'exports', '__filename', '__dirname', 'process', 'console', 'fs', source);
      fn(function (s) { return requireFrom(scriptPath, s); }, mod, mod.exports, scriptPath, __dirname(), process, console, fs);
    } catch (err) {
      if (err && err.__anvexProcessExit) return err.code;
      if (err) { AnvexNodeConsole.log('error', (err && err.stack) ? err.stack : String(err)); return 1; }
    }
    return __AnvexNodeState.exitCode;
  }
};
var __SCRIPT_PATH__ = '';
