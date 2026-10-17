package com.anvex.core.engine

/**
 * Offline starter templates (replaces upstream GitHub-repo starter templates,
 * which require network + git — see ARCHITECTURE_AUDIT.md §4).
 */
object ProjectTemplates {
    enum class Starter(val title: String) { BLANK("Blank"), LANDING("Landing page"), TODO("Todo app"), DASHBOARD("Dashboard"), REACT("React starter") }

    fun files(starter: Starter, name: String): List<Pair<String, String>> = when (starter) {
        Starter.BLANK -> listOf("README.md" to "# $name\n\nCreated with Anvex.")
        Starter.LANDING -> staticHtml(name)
        Starter.TODO -> todo(name)
        Starter.DASHBOARD -> dashboard(name)
        Starter.REACT -> reactMinimal(name)
    }

    private fun todo(name: String) = listOf(
        "index.html" to """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${name}</title><link rel="stylesheet" href="styles.css"></head><body><main><h1>${name}</h1><form id="form"><input id="task" placeholder="New task" required><button>Add</button></form><ul id="list"></ul></main><script src="app.js"></script></body></html>""",
        "styles.css" to "body{font-family:system-ui;margin:0;background:#f6f7fb}main{max-width:720px;margin:auto;padding:32px}input,button{padding:10px;border-radius:8px;border:1px solid #bbb}button{cursor:pointer}li{padding:8px}",
        "app.js" to "const f=document.querySelector('#form'),i=document.querySelector('#task'),l=document.querySelector('#list');f.onsubmit=e=>{e.preventDefault();const li=document.createElement('li');li.textContent=i.value;li.onclick=()=>li.remove();l.append(li);i.value='';i.focus()};"
    )
    private fun dashboard(name: String) = listOf(
        "index.html" to """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${name}</title><link rel="stylesheet" href="styles.css"></head><body><aside>Dashboard</aside><main><h1>Overview</h1><section><article><b>1,284</b><span>Users</span></article><article><b>€24.8K</b><span>Revenue</span></article><article><b>8.4%</b><span>Conversion</span></article></section></main></body></html>""",
        "styles.css" to "*{box-sizing:border-box}body{margin:0;font-family:system-ui;display:flex;min-height:100vh;background:#f5f7fb}aside{width:220px;padding:24px;font-weight:700;background:#18202d;color:white}main{padding:32px;flex:1}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px}article{background:white;padding:24px;border-radius:16px;display:flex;flex-direction:column;gap:8px}article b{font-size:28px}"
    )


    fun staticHtml(name: String): List<Pair<String, String>> = listOf(
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$name</title>
<link rel="stylesheet" href="styles.css">
</head>
<body>
<main>
  <h1>$name</h1>
  <p>Edit this project or ask the AI to build what you described.</p>
  <button id="btn">Tap me</button>
  <p id="out"></p>
</main>
<script src="app.js"></script>
</body>
</html>
""".trimIndent(),
        "styles.css" to """
:root { --accent: #4f7cff; }
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; background: #f6f8fc; color: #16233d; }
main { max-width: 640px; margin: 0 auto; padding: 32px 20px; }
h1 { color: var(--accent); }
button { background: var(--accent); color: #fff; border: 0; border-radius: 10px;
  padding: 12px 20px; font-size: 16px; cursor: pointer; }
""".trimIndent(),
        "app.js" to """
let count = 0;
const btn = document.getElementById('btn');
const out = document.getElementById('out');
btn.addEventListener('click', () => {
  count++;
  out.textContent = 'Tapped ' + count + ' time' + (count === 1 ? '' : 's');
});
""".trimIndent(),
    )

    fun reactMinimal(name: String): List<Pair<String, String>> = listOf(
        "package.json" to """
{
  "name": "${name.lowercase().replace(Regex("[^a-z0-9-]+"), "-")}",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  }
}
""".trimIndent(),
        "index.html" to """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$name</title>
</head>
<body>
<div id="root"></div>
<script type="module" src="/src/main.jsx"></script>
</body>
</html>
""".trimIndent(),
        "src/main.jsx" to """
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './index.css';

createRoot(document.getElementById('root')).render(<App />);
""".trimIndent(),
        "src/App.jsx" to """
import React, { useState } from 'react';

export default function App() {
  const [count, setCount] = useState(0);
  return (
    <main style={{ fontFamily: 'system-ui', padding: 24 }}>
      <h1 style={{ color: '#4f7cff' }}>React starter</h1>
      <p>Ask the AI to build your app in this project.</p>
      <button onClick={() => setCount(count + 1)}>Clicked {count} times</button>
    </main>
  );
}
""".trimIndent(),
        "src/index.css" to """
:root { --accent: #4f7cff; }
body { margin: 0; font-family: system-ui, sans-serif; color: #16233d; }
""".trimIndent(),
    )
}
