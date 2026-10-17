/**
 * Anvex Harness Bridge (v4.1 Nebula)
 * ---------------------------------------------------------------------------
 * The typed JS side of the Kotlin JS bridge (window.Android, injected by
 * HarnessScreen) plus the browser fallbacks required by GOAL 6:
 *
 *   1. In-app WebView  → Android.saveZip(base64, name)      (primary, mandated)
 *   2. In-app WebView  → Android.shareZip(base64, name)     (secondary share)
 *   3. In-app WebView  → /__anvex__/download?path=...    (v4.1 workspace files)
 *   4. Plain browser   → data:application/zip;base64,<b64>  (fallback — blob:
 *                        and URL.createObjectURL are blocked in the preview
 *                        WebView, so the data: URL is the only working path)
 *
 * v4.1 offline contract:
 *   - JSZip is BUNDLED LOCALLY as ./jszip.min.js (no CDN — the app must work
 *     fully offline; <script src="./jszip.min.js"> in index.html plus a local
 *     dynamic fallback below).
 *   - JetBrains Mono is bundled locally under ./fonts/ (see styles.css).
 *   - ZIP bytes always travel as base64 text over the bridge (saveZip →
 *     MediaStore.Downloads with the IS_PENDING publish flow) or as a data:
 *     URL in plain browsers. blob: URLs are NEVER created.
 *
 * Everything is promisified and event-driven; the React app never touches the
 * raw bridge surface directly.
 */
(function (global) {
  'use strict';

  const BRIDGE = global.Android || null;
  const eventListeners = {};
  let bridgeSeen = false;

  // ---------------------------------------------------------------- events

  /**
   * Kotlin → JS event pump. HarnessBridge evaluates:
   *   window.__harnessEvent && window.__harnessEvent('<json>')
   * (the payload is single-quoted with escaped backslashes/quotes/newlines).
   */
  global.__harnessEvent = function (payload) {
    let event;
    try { event = JSON.parse(payload); } catch (e) { return; }
    const type = event && event.type;
    if (!type) return;
    (eventListeners[type] || []).forEach(function (fn) { try { fn(event.payload || {}); } catch (e) { console.error(e); } });
    (eventListeners['*'] || []).forEach(function (fn) { try { fn(event); } catch (e) { console.error(e); } });
  };

  function on(type, fn) {
    (eventListeners[type] = eventListeners[type] || []).push(fn);
    return function () {
      eventListeners[type] = eventListeners[type].filter(function (f) { return f !== fn; });
    };
  }

  // ---------------------------------------------------------------- call helper

  function parseResult(raw) {
    if (raw == null) return { ok: false, error: 'bridge returned nothing' };
    try { return JSON.parse(raw); } catch (e) { return { ok: false, error: 'bridge returned invalid JSON' }; }
  }

  function call(method) {
    if (!BRIDGE || typeof BRIDGE[method] !== 'function') {
      return Promise.resolve({ ok: false, error: 'bridge method unavailable: ' + method });
    }
    const args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (resolve) {
      try { resolve(parseResult(BRIDGE[method].apply(BRIDGE, args))); }
      catch (e) { resolve({ ok: false, error: String(e && e.message || e) }); }
    });
  }

  function hasBridge() { return !!BRIDGE; }

  // ---------------------------------------------------------------- JSZip (offline)

  // v4.1: JSZip is bundled next to this file and referenced RELATIVELY — no CDN,
  // no network. index.html loads it via <script src="./jszip.min.js">; the
  // dynamic injection below is only a resilient second path.
  const JSZIP_LOCAL_SRC = './jszip.min.js';
  let jszipPromise = null;

  function loadJsZip() {
    if (global.JSZip) return Promise.resolve(global.JSZip);
    if (jszipPromise) return jszipPromise;
    jszipPromise = new Promise(function (resolve, reject) {
      const script = document.createElement('script');
      script.src = JSZIP_LOCAL_SRC;
      script.onload = function () { global.JSZip ? resolve(global.JSZip) : reject(new Error('JSZip failed to initialize')); };
      script.onerror = function () {
        jszipPromise = null; // allow a retry (e.g. after the server is restarted)
        reject(new Error('JSZip could not be loaded from the local bundle (./jszip.min.js). The harness is fully offline — the CDN path was removed in v4.1.'));
      };
      document.head.appendChild(script);
    });
    return jszipPromise;
  }

  // ---------------------------------------------------------------- GOAL 6: zip download

  /**
   * Assembles the full Anvex v4 source ZIP with JSZip (client side, exactly as
   * the v4 contract requires) from the bridge's chunked source reads, then:
   *  - with bridge  → Android.saveZip(base64, fileName)
   *  - without      → data:application/zip;base64 fallback (browser preview)
   *
   * @param {string} fileName target file name
   * @param {function} onProgress (done, total, label)
   */
  async function buildSourceZip(fileName, onProgress) {
    const JSZip = await loadJsZip();
    const zip = new JSZip();

    if (hasBridge() && typeof BRIDGE.sourceManifest === 'function') {
      const manifestResult = parseResult(BRIDGE.sourceManifest());
      if (!manifestResult.ok && !Array.isArray(manifestResult)) {
        throw new Error('source manifest unavailable: ' + (manifestResult.error || 'unknown'));
      }
      const entries = Array.isArray(manifestResult) ? manifestResult : manifestResult.entries || [];
      let done = 0;
      for (const entry of entries) {
        const path = entry.path.startsWith('source/') ? entry.path : 'source/' + entry.path;
        const size = entry.size || 0;
        const chunks = [];
        let offset = 0;
        const CHUNK = 384 * 1024; // stay under the bridge's 512 KB chunk cap
        while (offset < size) {
          const length = Math.min(CHUNK, size - offset);
          const chunkResult = parseResult(BRIDGE.readSourceFile(path, offset, length));
          if (!chunkResult.ok) throw new Error('reading ' + path + ' failed: ' + chunkResult.error);
          chunks.push(chunkResult.content); // base64
          offset += chunkResult.length;
          if (chunkResult.length === 0) break;
        }
        const base64 = chunks.join('');
        if (base64) zip.file(path, base64, { base64: true });
        done++;
        if (onProgress) onProgress(done, entries.length, path);
      }
    } else {
      // Browser fallback payload: this page's own sources (same-origin fetch) +
      // an honest README — the full source mirror is only reachable through the
      // in-app bridge.
      zip.file('README.txt', BROWSER_FALLBACK_README);
      const ownFiles = ['index.html', 'styles.css', 'bridge.js', 'app.jsx'];
      for (const f of ownFiles) {
        try {
          const text = await fetch(f).then(function (r) { return r.ok ? r.text() : null; });
          if (text != null) zip.file('harness/' + f, text);
        } catch (e) { /* offline or file:// — skip */ }
      }
    }
    return zip.generateAsync({ type: 'base64', compression: 'DEFLATE' });
  }

  const BROWSER_FALLBACK_README = [
    'Anvex v4.1 (Nebula) Harness — browser fallback export',
    '=========================================================',
    '',
    'This archive was produced by the data: URL fallback path (GOAL 6.4) because the',
    'Android JS bridge is not available in this context. It contains the harness web',
    'app files only.',
    '',
    'To export the complete Anvex v4.1 source, open the Harness screen inside the',
    'Anvex app: the button then routes through Android.saveZip (JS bridge) and',
    'saves Anvex-v4.1-Nebula-FIXED.zip into the system Downloads collection.',
    '',
  ].join('\n');

  /**
   * GOAL 6 primary path — called by the Download button (and the v4.1 self-test
   * panel). Resolves { ok, via, fileName }.
   *
   * v4.1 contract: with the bridge, Android.saveZip is called FIRE-AND-FORGET
   * with the base64 payload; Kotlin decodes it into MediaStore.Downloads
   * (IS_PENDING 1→0) and reports the real outcome through the `zip_saved` /
   * `zip_error` events (surfaced as toasts by app.jsx). Without the bridge the
   * data: URL fallback runs synchronously.
   */
  async function downloadSourceZip(fileName, onProgress) {
    const base64 = await buildSourceZip(fileName, onProgress);
    if (hasBridge() && typeof BRIDGE.saveZip === 'function') {
      BRIDGE.saveZip(base64, fileName); // base64 → MediaStore.Downloads (Kotlin side)
      return { ok: true, via: 'bridge', fileName: fileName };
    }
    return downloadBase64AsDataUrl(base64, fileName);
  }

  /** GOAL 6 secondary path — Share button. */
  async function shareSourceZip(fileName, onProgress) {
    const base64 = await buildSourceZip(fileName, onProgress);
    if (hasBridge() && typeof BRIDGE.shareZip === 'function') {
      const result = parseResult(BRIDGE.shareZip(base64, fileName));
      if (!result.ok) throw new Error(result.error || 'Android.shareZip rejected the payload');
      return { ok: true, via: 'bridge', fileName: result.fileName || fileName };
    }
    // No bridge: sharing is not possible — fall back to a plain download.
    return downloadBase64AsDataUrl(base64, fileName);
  }

  /**
   * data:application/zip;base64 fallback download. Note: URL.createObjectURL and
   * blob: URLs are intentionally NOT used — they are blocked in the preview
   * WebView, which is exactly the bug v4.0 fixes (and v4.1 keeps fixed).
   */
  function downloadBase64AsDataUrl(base64, fileName) {
    const href = 'data:application/zip;base64,' + base64;
    const a = document.createElement('a');
    a.href = href;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    return { ok: true, via: 'data-url', fileName: fileName };
  }

  // ------------------------------------------- v4.1 (Nebula): workspace download route

  /** URL of the loopback download route for a relative workspace path. */
  function workspaceDownloadUrl(relativePath) {
    return '/__anvex__/download?path=' + encodeURIComponent(relativePath);
  }

  /**
   * Downloads an existing workspace file (e.g. .anvex/attachments/<sha>*.zip)
   * through the HarnessWebServer attachment route — a plain <a> click, no blob:.
   * Works offline: the server is bound to 127.0.0.1 inside the app.
   */
  function downloadWorkspaceFile(relativePath) {
    const a = document.createElement('a');
    a.href = workspaceDownloadUrl(relativePath);
    a.download = String(relativePath).split('/').pop() || 'download.bin';
    document.body.appendChild(a);
    a.click();
    a.remove();
    return { ok: true, via: 'server-route', path: relativePath };
  }

  // ------------------------------------------- v4.1: download self-test panel (index.html)

  function sayStatus(text, cls) {
    const el = document.getElementById('downloadTestStatus');
    if (!el) return;
    el.textContent = text;
    el.className = 'dt-status' + (cls ? ' ' + cls : '');
  }

  /** Test 1 — full bridge export: JSZip (local) → base64 → Android.saveZip. */
  function testBridgeExport() {
    const fileName = global.BoltHarness && global.BoltHarness.DEFAULT_ZIP_NAME
      ? global.BoltHarness.DEFAULT_ZIP_NAME : 'Anvex-v4.1-Nebula-FIXED.zip';
    sayStatus('Assembling ZIP with local JSZip…');
    return downloadSourceZip(fileName, function (done, total, label) {
      sayStatus('Packing ' + done + '/' + total + ' — ' + label);
    }).then(function (result) {
      sayStatus(result.via === 'bridge'
        ? '✓ handed to Android.saveZip → MediaStore Downloads (watch for the “Saved” toast)'
        : '✓ data: URL fallback download triggered (no bridge in this context)', 'ok');
      return result;
    }).catch(function (e) {
      sayStatus('✗ export failed: ' + (e && e.message || e), 'err');
      throw e;
    });
  }

  /** Test 2 — existing workspace file through /__anvex__/download (HEAD probe, then <a> click). */
  function testServerDownload(relativePath) {
    const path = String(relativePath || '').trim();
    sayStatus('Probing ' + workspaceDownloadUrl(path));
    return fetch(workspaceDownloadUrl(path), { method: 'HEAD' }).then(function (r) {
      if (!r.ok) {
        sayStatus('✗ HTTP ' + r.status + ' — check the path (404 = not found, 400 = unsafe path)', 'err');
        throw new Error('HTTP ' + r.status);
      }
      downloadWorkspaceFile(path);
      sayStatus('✓ attachment download started: ' + path, 'ok');
    }).catch(function (e) {
      if (String(e && e.message).indexOf('HTTP') === -1) {
        sayStatus('✗ server route unreachable: ' + (e && e.message || e), 'err');
      }
      throw e;
    });
  }

  /** Prefills the path input with the first .anvex/attachments file, if any. */
  function prefillAttachmentPath() {
    const input = document.getElementById('dlPath');
    if (!input || (input.value && input.value.trim())) return;
    if (!hasBridge() || typeof BRIDGE.listFiles !== 'function') return;
    try {
      const result = parseResult(BRIDGE.listFiles());
      const files = (result && result.files) || [];
      const hit = files.find(function (p) { return typeof p === 'string' && p.indexOf('.anvex/attachments/') !== -1; })
        || files.find(function (p) { return typeof p === 'string' && p.indexOf('.anvex/') === 0; });
      if (hit) input.value = hit;
    } catch (e) { /* workspace not ready — the user can type a path */ }
  }

  /** Wires the index.html self-test panel (called by the inline script). */
  function installDownloadTestPanel() {
    prefillAttachmentPath();
    setTimeout(prefillAttachmentPath, 2500); // retry once the workspace materializes
    const btnExport = document.getElementById('btnExportZip');
    const btnServer = document.getElementById('btnServerDownload');
    const pathInput = document.getElementById('dlPath');
    if (btnExport) {
      btnExport.addEventListener('click', function () { testBridgeExport().catch(function () {}); });
    }
    if (btnServer) {
      btnServer.addEventListener('click', function () {
        const fallback = hasBridge() ? '.anvex/attachments/' : 'index.html';
        testServerDownload((pathInput && pathInput.value) || fallback).catch(function () {});
      });
    }
  }

  // ---------------------------------------------------------------- exports

  global.BoltHarness = {
    on: on,
    call: call,
    hasBridge: hasBridge,
    loadJsZip: loadJsZip,
    buildSourceZip: buildSourceZip,
    downloadSourceZip: downloadSourceZip,
    shareSourceZip: shareSourceZip,
    downloadBase64AsDataUrl: downloadBase64AsDataUrl,
    workspaceDownloadUrl: workspaceDownloadUrl,
    downloadWorkspaceFile: downloadWorkspaceFile,
    testBridgeExport: testBridgeExport,
    testServerDownload: testServerDownload,
    installDownloadTestPanel: installDownloadTestPanel,
    DEFAULT_ZIP_NAME: 'Anvex-v4.1-Nebula-FIXED.zip',
  };
})(window);
