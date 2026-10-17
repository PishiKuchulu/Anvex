package com.anvex.core.engine

/**
 * Generates the preview bootstrap page for React/TSX projects.
 *
 * Architecture (brief §15 "embedded JavaScript runtime / hybrid runtime"):
 * the System WebView's V8 is the embedded JS engine. This harness:
 *  1. loads the OFFLINE runtime bundle served by the in-app localhost server
 *     (`/__runtime__/babel.min.js` = bundled @babel/standalone,
 *     `/__runtime__/react.umd.js` + `react-dom.umd.js` = bundled React 18) —
 *     v1.1 §11: no CDN and no internet needed for React/JSX transpilation;
 *  2. fetches project sources from the in-app localhost server,
 *  3. transpiles JSX/TS in-browser with the bundled Babel,
 *  4. maps react/react-dom bare imports to local ESM wrappers; OTHER bare
 *     imports (chart libs etc.) resolve from esm.sh and need internet,
 *  5. executes the module graph in dependency order with a CommonJS shim,
 *  6. forwards console + errors to the app through the JS bridge.
 *
 * The JS below intentionally avoids template literals so it can live in a Kotlin
 * raw string without escaping.
 */
object PreviewHarness {

    /** Route prefix where the preview server exposes bundled runtime assets. */
    const val RUNTIME_ROUTE = "/__runtime__/"

    /** Assets shipped in the APK (app/src/main/assets/runtime/). */
    const val ASSET_BABEL = "babel.min.js"
    const val ASSET_REACT_UMD = "react.umd.js"
    const val ASSET_REACT_DOM_UMD = "react-dom.umd.js"

    /**
     * Bundled mermaid v12 (architect review item 3: Claude-grade artifacts).
     * Served OFFLINE from the runtime route — diagram previews never touch a CDN.
     */
    const val ASSET_MERMAID = "mermaid.min.js"

    /**
     * ESM wrapper modules generated over the UMD globals. The complete set of
     * runtime routes the preview server may ever serve (whitelist basis).
     */
    val WRAPPER_ASSETS: Set<String> = setOf(
        "react.js",
        "react-dom.js",
        "react-dom-client.js",
        "react-jsx-runtime.js",
    )

    /**
     * Bare specifiers that resolve OFFLINE from the bundled runtime.
     * Everything else in package.json resolves from esm.sh (online).
     */
    val OFFLINE_SPECIFIERS: Map<String, String> = mapOf(
        "react" to "$RUNTIME_ROUTE/react.js",
        "react-dom" to "$RUNTIME_ROUTE/react-dom.js",
        "react-dom/client" to "$RUNTIME_ROUTE/react-dom-client.js",
        "react/jsx-runtime" to "$RUNTIME_ROUTE/react-jsx-runtime.js",
        "react/jsx-dev-runtime" to "$RUNTIME_ROUTE/react-jsx-runtime.js",
    )

    fun generate(
        manifestJson: String,
        entryPath: String,
        cssLinks: List<String>,
        importMapJson: String,
    ): String {
        val cssTags = cssLinks.joinToString("\n") { """<link rel="stylesheet" href="$it">""" }
        return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Anvex Preview</title>
$cssTags
<script src="$RUNTIME_ROUTE$ASSET_REACT_UMD"></script>
<script src="$RUNTIME_ROUTE$ASSET_REACT_DOM_UMD"></script>
<script src="$RUNTIME_ROUTE$ASSET_BABEL"></script>
<style>
  html,body{margin:0;padding:0;font-family:system-ui,sans-serif;background:#fff}
  #__bolt_offline{display:none;position:fixed;inset:0;padding:32px;background:#0b1020;color:#e6edf3;
    font:14px/1.6 system-ui;z-index:2147483647;overflow:auto}
  #__bolt_offline h2{margin-top:0;color:#ffd479}
  #__bolt_offline code{background:#161b22;padding:2px 6px;border-radius:4px;color:#79c0ff}
</style>
</head>
<body>
<div id="root"></div>
<div id="__bolt_offline">
  <h2>Preview runtime failed to load</h2>
  <p>The offline runtime bundle (Babel + React) could not be loaded from the
  local server. React, JSX/TSX and static previews normally run fully offline;
  only third-party libraries outside the bundle (e.g. chart libraries declared
  in package.json) need internet.</p>
</div>
<script>
window.__boltManifest = $manifestJson;
window.__boltEntry = ${jsStringLiteral(entryPath)};
window.__boltImportMap = $importMapJson;
</script>
<script>
(function () {
  "use strict";
  var bridge = window.__anvex || null;
  function post(type, payload) {
    if (bridge) { try { bridge.post(JSON.stringify({ type: type, payload: payload })); } catch (e) {} }
  }
  function fmt(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      try { parts.push(typeof a === "string" ? a : JSON.stringify(a)); } catch (e) { parts.push(String(a)); }
    }
    return parts.join(" ");
  }
  ["log", "info", "warn", "error"].forEach(function (level) {
    console[level] = function () { post("console", { level: level, text: fmt(arguments) }); };
  });
  window.onerror = function (msg, src, line, col) {
    post("error", { message: String(msg), source: String(src), line: line, col: col });
    return false;
  };
  window.addEventListener("unhandledrejection", function (ev) {
    var reason = ev.reason && ev.reason.message ? ev.reason.message : String(ev.reason);
    post("error", { message: "Unhandled promise rejection: " + reason });
  });

  function runtimeFailure() {
    var el = document.getElementById("__bolt_offline");
    if (el) el.style.display = "block";
    post("error", { message: "Local runtime bundle failed to load. This is unexpected — react/jsx previews are bundled offline." });
  }

  function start() {
    if (typeof Babel === "undefined" || typeof React === "undefined" || typeof ReactDOM === "undefined") {
      runtimeFailure();
      return;
    }

    var manifest = window.__boltManifest || [];
    var entry = window.__boltEntry || "src/main.jsx";
    var importMap = window.__boltImportMap || {};
    var textCache = {};
    var transformed = {};
    var requiresOf = {};
    var esmCache = {};
    var executed = {};
    var moduleDefs = {};

    function isJsPath(p) { return /\.(js|jsx|ts|tsx|mjs|cjs)$/i.test(p); }

    function normalize(p) {
      var parts = p.split("/");
      var out = [];
      for (var i = 0; i < parts.length; i++) {
        if (parts[i] === "" || parts[i] === ".") continue;
        if (parts[i] === "..") { if (out.length) out.pop(); continue; }
        out.push(parts[i]);
      }
      return out.join("/");
    }
    function dirname(p) {
      var i = p.lastIndexOf("/");
      return i === -1 ? "" : p.substring(0, i);
    }
    function resolveModule(base) {
      var candidates = [base, base + ".js", base + ".jsx", base + ".ts", base + ".tsx",
        base + ".mjs", base + ".json", base + ".css",
        base + "/index.js", base + "/index.jsx", base + "/index.ts", base + "/index.tsx"];
      for (var i = 0; i < candidates.length; i++) {
        if (transformed[candidates[i]] !== undefined || textCache[candidates[i]] !== undefined) {
          return candidates[i];
        }
      }
      return null;
    }

    var chain = Promise.resolve();

    // 1. fetch all candidate files (text) into cache
    manifest.forEach(function (f) {
      var p = f.path;
      if (isJsPath(p) || /\.json$/i.test(p) || /\.css$/i.test(p)) {
        chain = chain.then(function () {
          return fetch("/" + p).then(function (r) { return r.ok ? r.text() : null; })
            .then(function (t) { if (t !== null) textCache[p] = t; });
        }).catch(function () {});
      }
    });

    chain = chain.then(function () {
      // 2. transform all JS-like files (offline Babel)
      Object.keys(textCache).forEach(function (p) {
        if (!isJsPath(p)) return;
        var out;
        try {
          out = Babel.transform(textCache[p], {
            presets: [["react"], ["typescript", { isTSX: true, allExtensions: true }]],
            plugins: ["transform-modules-commonjs"],
            filename: p
          }).code;
        } catch (e) {
          post("error", { message: "Transpile failed for " + p + ": " + (e && e.message ? e.message : e) });
          throw e;
        }
        transformed[p] = out;
        var specs = [];
        var re = /require\((["'])([^"']+)\1\)/g;
        var m;
        while ((m = re.exec(out))) { specs.push(m[2]); }
        requiresOf[p] = specs;
      });

      // 3. collect bare specifiers
      var bare = {};
      Object.keys(requiresOf).forEach(function (p) {
        requiresOf[p].forEach(function (spec) {
          if (spec.charAt(0) !== "." && spec.charAt(0) !== "/" && !/\.(css|json)$/i.test(spec)) {
            bare[spec] = true;
          }
        });
      });

      // 4. preload bare deps: react family is OFFLINE (global UMD); others via CDN
      function jsxImpl(type, props, key) {
        var config = {};
        for (var k in props) { config[k] = props[k]; }
        if (key !== undefined) config.key = key;
        return window.React.createElement(type, config);
      }
      function localShim(spec) {
        if (spec === "react") {
          return { default: window.React, __esModule: true };
        }
        if (spec === "react/jsx-runtime" || spec === "react/jsx-dev-runtime") {
          return {
            default: window.React,
            __esModule: true,
            jsx: jsxImpl,
            jsxs: jsxImpl,
            jsxDEV: jsxImpl,
            Fragment: window.React.Fragment
          };
        }
        if (spec === "react-dom") return { default: window.ReactDOM, __esModule: true };
        if (spec === "react-dom/client") return { createRoot: window.ReactDOM.createRoot, hydrateRoot: window.ReactDOM.hydrateRoot };
        return null;
      }
      var loads = Object.keys(bare).map(function (spec) {
        var shim = localShim(spec);
        if (shim) { esmCache[spec] = shim; return Promise.resolve(null); }
        var url = importMap[spec] || ("https://esm.sh/" + spec);
        return import(url).then(function (mod) { esmCache[spec] = mod; })
          .catch(function (e) {
            post("error", { message: "Failed to load dependency '" + spec + "' from CDN: " + (e && e.message ? e.message : e) + ". Only bundled libraries (react, react-dom) work offline." });
          });
      });
      return Promise.all(loads);
    });

    chain = chain.then(function () {
      // 5. execute module graph from entry
      function requireFrom(spec, fromPath) {
        if (/\.(css)$/i.test(spec)) {
          var cssPath = resolveModule(normalize(dirname(fromPath) + "/" + spec)) ||
                       resolveModule(normalize(spec));
          if (cssPath && textCache[cssPath] !== undefined && !document.querySelector("style[data-bolt='" + cssPath + "']")) {
            var st = document.createElement("style");
            st.setAttribute("data-bolt", cssPath);
            st.textContent = textCache[cssPath];
            document.head.appendChild(st);
          }
          return {};
        }
        if (/\.(json)$/i.test(spec)) {
          var jsonPath = resolveModule(normalize(dirname(fromPath) + "/" + spec)) || resolveModule(normalize(spec));
          if (jsonPath && textCache[jsonPath] !== undefined) {
            return { default: JSON.parse(textCache[jsonPath]) };
          }
          return {};
        }
        if (spec.charAt(0) === "." || spec.charAt(0) === "/") {
          var base = spec.charAt(0) === "/" ? normalize(spec) : normalize(dirname(fromPath) + "/" + spec);
          var target = resolveModule(base);
          if (!target) throw new Error("Cannot resolve module '" + spec + "' from " + fromPath);
          return execModule(target).exports;
        }
        if (esmCache[spec] === undefined) throw new Error("Module not preloaded: " + spec);
        return esmCache[spec];
      }

      function execModule(path) {
        if (executed[path]) return moduleDefs[path];
        executed[path] = true;
        var module = { exports: {} };
        moduleDefs[path] = module;
        var fn = new Function("require", "module", "exports", transformed[path]);
        fn(function (spec) { return requireFrom(spec, path); }, module, module.exports);
        return module;
      }

      var entryResolved = resolveModule(entry) || entry;
      if (transformed[entryResolved] === undefined) {
        throw new Error("Entry module not found: " + entry);
      }
      execModule(entryResolved);
      post("ready", { entry: entryResolved });
    });

    chain.catch(function (e) {
      post("error", { message: (e && e.message ? e.message : String(e)) });
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
</script>
</body>
</html>"""
    }
    // ------------------------------------------------------- diagram artifacts
    // Architect review item 3: artifact types "mermaid" and "svg". File-based
    // preview dispatch (StaticPreviewServer) maps .mmd/.mermaid/.svg project
    // files onto these generators so diagrams preview like HTML/React files do.

    /** True for Mermaid diagram sources (rendered through the offline mermaid bundle). */
    fun isMermaidPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".mmd") || lower.endsWith(".mermaid")
    }

    /** True for standalone SVG files (rendered through the sandboxed inline-SVG page). */
    fun isSvgPath(path: String): Boolean = path.lowercase().endsWith(".svg")

    /** Either diagram kind — used by the preview server's dispatch. */
    fun isDiagramPath(path: String): Boolean = isMermaidPath(path) || isSvgPath(path)

    /**
     * Builds the preview page for whichever diagram type [path] is. Mermaid
     * sources render with the bundled mermaid runtime; SVG markup renders in the
     * sandboxed inline container. Unknown extensions fall back to mermaid.
     */
    fun generateDiagramPage(path: String, source: String): String =
        if (isSvgPath(path)) generateSvgPage(source, path) else generateMermaidPage(source, path)

    /**
     * Preview page for the "mermaid" artifact type: renders Mermaid diagram text
     * fully OFFLINE with the bundled [ASSET_MERMAID] (same pattern as Babel/React —
     * v1.1 §11: no CDN, no internet). Dark theme variables follow the design
     * system (background #0b101e, primaryColor #06b6d4, primaryTextColor #f8fafc,
     * lineColor #38bdf8).
     *
     * Honest failure states: parse errors are shown VERBATIM in the preview area
     * labeled "Diagram parse error" (mermaid's own descriptive error text — never
     * hidden), and a missing local runtime is reported explicitly instead of
     * silently rendering nothing.
     */
    fun generateMermaidPage(source: String, filePath: String): String = """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${htmlEscape(filePath)} — Anvex Diagram Preview</title>
<script src="$RUNTIME_ROUTE$ASSET_MERMAID"></script>
<style>
  html,body{margin:0;padding:0;background:#0b101e;color:#f8fafc;font-family:system-ui,sans-serif}
  .hdr{padding:10px 16px 0;font:12px/1.5 ui-monospace,monospace;color:#94a3b8;word-break:break-all}
  #diagram{padding:16px;overflow:auto}
  #diagram svg{max-width:100%;height:auto}
  #parse-error{display:none;margin:16px;padding:16px;border:1px solid #f87171;border-radius:10px;background:#1a0d12}
  #parse-error h2{margin:0 0 8px;font:600 14px system-ui;color:#f87171}
  #parse-error pre{margin:0;white-space:pre-wrap;word-break:break-word;font:12px/1.6 ui-monospace,monospace;color:#fecaca}
  #runtime-missing{display:none;margin:16px;padding:16px;border:1px solid #fbbf24;border-radius:10px;
    color:#fde68a;font:14px/1.6 system-ui}
  details{margin:0 16px 16px;border:1px solid #1e293b;border-radius:10px;background:#0d1424}
  summary{padding:8px 12px;cursor:pointer;font:12px system-ui;color:#94a3b8}
  pre.src{margin:0;padding:12px;white-space:pre-wrap;font:12px/1.6 ui-monospace,monospace;color:#cbd5e1}
</style>
</head>
<body>
<div class="hdr">${htmlEscape(filePath)}</div>
<div id="diagram"></div>
<div id="parse-error">
  <h2>Diagram parse error</h2>
  <pre id="parse-error-text"></pre>
</div>
<div id="runtime-missing">
  Mermaid runtime failed to load from the local bundle. Diagram previews are
  bundled offline (mermaid.min.js); this failure is unexpected.
</div>
<details>
  <summary>Mermaid source</summary>
  <pre class="src">${htmlEscape(source)}</pre>
</details>
<script>
(function () {
  "use strict";
  var diagramSource = ${jsStringLiteral(source)};
  var bridge = window.__anvex || null;
  function post(type, payload) {
    if (bridge) { try { bridge.post(JSON.stringify({ type: type, payload: payload })); } catch (e) {} }
  }
  function showError(message) {
    var box = document.getElementById("parse-error");
    var text = document.getElementById("parse-error-text");
    if (text) text.textContent = message;
    if (box) box.style.display = "block";
    post("error", { message: "Diagram parse error: " + message });
  }
  function start() {
    if (typeof mermaid === "undefined") {
      var el = document.getElementById("runtime-missing");
      if (el) el.style.display = "block";
      post("error", { message: "Local Mermaid runtime failed to load. Diagram previews are bundled offline; this is unexpected." });
      return;
    }
    try {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: "dark",
        themeVariables: {
          darkMode: true,
          background: "#0b101e",
          primaryColor: "#06b6d4",
          primaryTextColor: "#f8fafc",
          lineColor: "#38bdf8"
        }
      });
    } catch (e) {
      showError(String((e && e.message) || e));
      return;
    }
    // The diagram source is embedded as an escaped string literal and rendered
    // with securityLevel "strict" (mermaid sanitizes labels via DOMPurify).
    mermaid.render("anvex-diagram", diagramSource).then(function (result) {
      document.getElementById("diagram").innerHTML = result.svg;
      post("ready", { artifact: "mermaid" });
    }).catch(function (err) {
      // Parse errors are surfaced verbatim — never hidden.
      showError(String((err && err.message) || err));
    });
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
</script>
</body>
</html>"""

    /**
     * Preview page for the "svg" artifact type: inline SVG markup rendered inside
     * a sandboxed container. The markup is sanitized server-side by
     * [sanitizeInlineSvg] (scripts, event handlers and external references are
     * removed), and the page ships a strict Content-Security-Policy that blocks
     * every script and network load — so the diagram is viewable but inert.
     * The page deliberately contains NO JavaScript.
     */
    fun generateSvgPage(svgMarkup: String, filePath: String): String {
        val clean = sanitizeInlineSvg(svgMarkup)
        val notice = if (clean.removedAnything) {
            """<div class="notice">Sanitized — ${htmlEscape(clean.summary)}.</div>"""
        } else {
            ""
        }
        return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: 'self'; font-src data: 'self'">
<title>${htmlEscape(filePath)} — Anvex SVG Preview</title>
<style>
  html,body{margin:0;padding:0;background:#0b101e;color:#f8fafc;font-family:system-ui,sans-serif}
  .hdr{padding:10px 16px 0;font:12px/1.5 ui-monospace,monospace;color:#94a3b8;word-break:break-all}
  .sub{padding:2px 16px 0;font:11px/1.5 system-ui;color:#64748b}
  .notice{margin:12px 16px 0;padding:8px 12px;border:1px solid #fbbf24;border-radius:8px;
    color:#fde68a;font:12px/1.5 system-ui}
  #stage{padding:16px;overflow:auto}
  #stage svg{max-width:100%;height:auto}
</style>
</head>
<body>
<div class="hdr">${htmlEscape(filePath)}</div>
<div class="sub">Scripts and external resources are disabled in SVG preview.</div>
$notice
<div id="stage">
${clean.markup}
</div>
</body>
</html>"""
    }

    /** Result of [sanitizeInlineSvg] — what was removed, disclosed honestly. */
    data class SvgSanitizeResult(
        val markup: String,
        val removedScriptBlocks: Int,
        val removedEventHandlers: Int,
        val removedExternalReferences: Int,
    ) {
        val removedAnything: Boolean
            get() = removedScriptBlocks + removedEventHandlers + removedExternalReferences > 0

        /** Short human-readable disclosure, e.g. "removed 1 script block, 2 event handlers". */
        val summary: String
            get() = buildList {
                if (removedScriptBlocks > 0) add("$removedScriptBlocks script block(s)")
                if (removedEventHandlers > 0) add("$removedEventHandlers event handler(s)")
                if (removedExternalReferences > 0) add("$removedExternalReferences external/javascript reference(s)")
            }.joinToString(", ").let { "removed $it" }
    }

    /**
     * Sanitizes inline SVG markup for the sandboxed SVG preview page:
     *  1. removes `<script>` blocks (and truncates at an unterminated `<script`,
     *     which the HTML parser would otherwise treat as raw text);
     *  2. removes inline event-handler attributes (`on*`, including SMIL handlers);
     *  3. removes `href`/`xlink:href`/`src` attributes whose value is NOT a
     *     fragment (`#…`), an inline data URI (image or font), or a colon-free
     *     relative URL — this drops `javascript:`, `http(s)://`, protocol-relative
     *     and entity-encoded scheme URLs in one whitelist pass;
     *  4. neutralizes external `url(http…)` / `@import "http…"` references in
     *     embedded CSS.
     *
     * Conservative by design: ambiguous constructs are removed, never guessed safe.
     */
    fun sanitizeInlineSvg(markup: String): SvgSanitizeResult {
        var removedScripts = 0
        var removedHandlers = 0
        var removedExternal = 0

        // 1. Paired <script>…</script> blocks.
        val scriptBlock = Regex("<script\\b[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE)
        var out = scriptBlock.replace(markup) { removedScripts++; "" }

        // 1b. Unterminated <script …> — the HTML parser treats everything after it
        // as raw text; drop the remainder instead of risking it.
        val openScript = Regex("<script\\b[^>]*/?>", RegexOption.IGNORE_CASE)
        openScript.find(out)?.let { first ->
            removedScripts++
            out = out.substring(0, first.range.first)
        }

        // 2. Inline event-handler attributes (onload=, onclick=, onbegin=, …).
        val handler = Regex("""\s+on[a-zA-Z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]*)""")
        out = handler.replace(out) { removedHandlers++; "" }

        // 3. URL attributes — whitelist: fragments, data:image|font URIs, colon-free
        // relative URLs. Entity decoding of the value BEFORE the check closes the
        // `&#106;avascript&#58;` obfuscation hole (the check decodes; the output
        // keeps the original attribute only when the decoded value is safe).
        val urlAttr = Regex("""\s(?:href|xlink:href|src)\s*=\s*("[^"]*"|'[^']*'|[^\s>]*)""", RegexOption.IGNORE_CASE)
        val entity = Regex("&#x([0-9a-fA-F]+);|&#([0-9]+);")
        fun decodeEntities(v: String): String = entity.replace(v) { m ->
            val code = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val radix = if (m.groupValues[1].isNotEmpty()) 16 else 10
            code.toIntOrNull(radix)?.toChar()?.toString() ?: m.value
        }
        out = urlAttr.replace(out) { m ->
            val raw = m.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            val decoded = decodeEntities(raw).trim()
            val lower = decoded.lowercase()
            val safe = decoded.isEmpty() || decoded.startsWith("#") ||
                lower.startsWith("data:image/") || lower.startsWith("data:font/") ||
                (!decoded.contains(":") && !decoded.startsWith("//"))
            if (safe) m.value else { removedExternal++; "" }
        }

        // 4. External references inside embedded CSS.
        val cssUrl = Regex("""url\(\s*['"]?(?:https?:)?//[^)]*['"]?\s*\)""", RegexOption.IGNORE_CASE)
        out = cssUrl.replace(out) { removedExternal++; "none" }
        val cssImport = Regex("""@import\s+['"](?:https?:)?//[^'"]*['"]\s*;?""", RegexOption.IGNORE_CASE)
        out = cssImport.replace(out) { removedExternal++; "" }

        return SvgSanitizeResult(out, removedScripts, removedHandlers, removedExternal)
    }

    /** Escapes untrusted text for safe inclusion as HTML text content. */
    private fun htmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }

    /** Encode untrusted project-derived text as a safe JavaScript string literal.
     * Escaping '<' is intentional: it prevents a literal </script> from terminating
     * the enclosing HTML script element before JavaScript parsing begins.
     */
    private fun jsStringLiteral(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003C")
                '>' -> append("\\u003E")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch.code < 0x20) append("\\u%04X".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

}
