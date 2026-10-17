/**
 * Anvex Harness v4 — the OPENCODE-style agent workspace (React, offline runtime).
 *
 * Panes: Agent (durable tool-call loop + artifacts), Files (tree + watcher +
 * diagnostics), Git (GitLite), Terminal (PTY), Skills (store), MCP, Build
 * (in-app Gradle), Diagnostics. All Android capabilities flow through the
 * BoltHarness bridge; in a plain browser the page degrades honestly.
 */
const { useState, useEffect, useRef, useCallback, useMemo } = React;

const CHAT_MODE_PLACEHOLDER = 'Describe a change — the agent plans, calls tools, and streams diffs…';

/* ------------------------------------------------------------------ utils */

function fmtBytes(n) {
  if (n == null) return '';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}

function fmtTime(ts) {
  const d = new Date(ts);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function relTime(ts) {
  const diff = Date.now() - ts;
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
  if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
  return Math.floor(diff / 86400000) + 'd ago';
}

function useToasts() {
  const [toasts, setToasts] = useState([]);
  const push = useCallback(function (title, message, kind) {
    const id = Math.random().toString(36).slice(2);
    setToasts(function (t) { return t.concat([{ id: id, title: title, message: message, kind: kind || 'info' }]); });
    setTimeout(function () {
      setToasts(function (t) { return t.filter(function (x) { return x.id !== id; }); });
    }, 6000);
  }, []);
  return { toasts: toasts, push: push };
}

/* ------------------------------------------------------------------ top bar */

function TopBar(props) {
  const [zipBusy, setZipBusy] = useState(false);
  const [zipProgress, setZipProgress] = useState(null);

  async function download() {
    if (zipBusy) return;
    setZipBusy(true);
    setZipProgress('assembling…');
    try {
      const result = await BoltHarness.downloadSourceZip(BoltHarness.DEFAULT_ZIP_NAME, function (done, total, label) {
        setZipProgress(done + '/' + total + ' · ' + label.split('/').pop());
      });
      if (result.via === 'bridge') {
        props.toast('Download', 'ZIP handed to Android.saveZip — saving ' + result.fileName, 'ok');
      } else {
        props.toast('Download', 'Browser fallback: started via data: URL (' + result.fileName + ')', 'ok');
      }
    } catch (e) {
      props.toast('Download failed', String(e.message || e), 'err');
    } finally {
      setZipBusy(false);
      setZipProgress(null);
    }
  }

  async function share() {
    if (zipBusy) return;
    setZipBusy(true);
    setZipProgress('assembling…');
    try {
      const result = await BoltHarness.shareSourceZip(BoltHarness.DEFAULT_ZIP_NAME, function (done, total, label) {
        setZipProgress(done + '/' + total + ' · ' + label.split('/').pop());
      });
      props.toast('Share ZIP', result.via === 'bridge' ? 'Share sheet opened for ' + result.fileName : 'No bridge — downloaded instead', 'ok');
    } catch (e) {
      props.toast('Share failed', String(e.message || e), 'err');
    } finally {
      setZipBusy(false);
      setZipProgress(null);
    }
  }

  const provider = props.state;
  return (
    <header className="topbar">
      <div className="brand"><span className="bolt">⚡</span>Anvex<span className="ver">v4.1</span></div>
      <div className="project-name chip">{provider.projectName || 'workspace'}</div>
      <span className={'chip ' + (provider.hasProvider ? 'ok' : 'warn')}>
        <span className="dot"></span>{provider.hasProvider ? 'provider ready' : 'no provider'}
      </span>
      <div className="spacer"></div>
      {zipProgress && <span className="chip">{zipProgress}</span>}
      <button className="btn small" disabled={zipBusy} onClick={download} title="Download the complete v4.1 source as a ZIP (GOAL 6: JS bridge save)">
        ⬇ Download ZIP v4
      </button>
      <button className="btn small ghost" disabled={zipBusy} onClick={share} title="Share the ZIP through the Android share sheet">
        Share
      </button>
    </header>
  );
}

/* ------------------------------------------------------------------ rail */

const PANES = [
  { id: 'agent', label: 'AI', title: 'Agent', hint: 'Durable agent loop' },
  { id: 'files', label: 'FS', title: 'Files', hint: 'Workspace + watcher + diagnostics' },
  { id: 'git', label: 'GIT', title: 'Git', hint: 'Local repository (GitLite)' },
  { id: 'terminal', label: 'SH', title: 'Terminal', hint: 'Interactive shell session' },
  { id: 'skills', label: 'SK', title: 'Skills', hint: 'Skill store' },
  { id: 'mcp', label: 'MCP', title: 'MCP', hint: 'Model Context Protocol' },
  { id: 'build', label: 'BLD', title: 'Build APK', hint: 'In-app Gradle' },
  { id: 'diag', label: 'DX', title: 'Diagnostics', hint: 'Lightweight diagnostics' },
];

function Rail(props) {
  return (
    <nav className="rail">
      {PANES.map(function (p) {
        return (
          <button key={p.id}
            className={'rail-btn' + (props.active === p.id ? ' active' : '')}
            onClick={function () { props.onSelect(p.id); }}
            title={p.title + ' — ' + p.hint}>
            {p.label}
          </button>
        );
      })}
    </nav>
  );
}

/* ------------------------------------------------------------------ agent pane */

function ArtifactCardView(props) {
  const artifact = props.artifact;
  return (
    <div className="artifact">
      <div className="artifact-head">
        <span className="artifact-title">◆ {artifact.title || 'artifact'}</span>
        <div className="spacer" style={{ flex: 1 }}></div>
        <span className="faint small">{artifact.actions.length} action(s)</span>
      </div>
      <div className="artifact-actions">
        {artifact.actions.map(function (a) {
          const create = (a.note || '').toUpperCase().indexOf('CREATE') >= 0;
          return (
            <div key={a.actionId} className="action-line">
              <span className={'action-marker' + (create ? ' create' : '')}>{create ? '+' : a.type === 'file' ? '~' : '$'}</span>
              {a.filePath
                ? <button className="action-path" onClick={function () { props.onOpenFile(a.filePath); }}>{a.filePath}</button>
                : <span className="dim">{(a.command || a.type || '').slice(0, 90)}</span>}
              {a.note && <span className="action-note">{a.note}</span>}
              <span className={'action-status ' + a.status}>{a.status}</span>
            </div>
          );
        })}
        {props.onRevert && artifact.actions.some(function (a) { return a.filePath; }) && (
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn small" onClick={props.onRevert}>Revert last changes</button>
          </div>
        )}
      </div>
    </div>
  );
}

function AgentPane(props) {
  const [input, setInput] = useState('');
  const listRef = useRef(null);
  const { messages, stream, artifact } = props;

  useEffect(function () {
    const node = listRef.current;
    if (node && node.lastChild) node.lastChild.scrollIntoView({ block: 'end' });
  }, [messages.length, stream, artifact]);

  function send() {
    const text = input.trim();
    if (!text || props.streaming) return;
    setInput('');
    const result = props.onSend(text);
    if (result && result.error) props.toast('Agent', result.error, 'err');
  }

  return (
    <div className="section">
      <div className="section-head">
        <span>Agent</span>
        {props.streaming && <span className="chip warn"><span className="dot"></span>streaming</span>}
        <div className="spacer"></div>
        <span className="faint">bolt protocol · durable tool calls</span>
      </div>
      <div className="transcript" ref={listRef}>
        {messages.length === 0 && !stream && (
          <div className="empty">
            <div style={{ fontSize: 28, marginBottom: 8 }}>⚡</div>
            No turns yet. Describe a change — the agent will plan, call tools
            (read/write/edit/shell/git) and stream the results here.
          </div>
        )}
        {messages.map(function (m) {
          const isUser = m.role === 'USER';
          return (
            <div key={m.id} className={'msg' + (isUser ? ' user' : '')}>
              <div className="who">{isUser ? 'you' : 'agent'}</div>
              <div className="bubble">
                {m.content}
                {m.error && <div className="error-text">{m.error}</div>}
              </div>
              <div className="meta">{fmtTime(m.createdAt)}{m.artifact ? ' · artifact: ' + m.artifact.title : ''}</div>
            </div>
          );
        })}
        {props.streaming && (stream || artifact) && (
          <div className="msg">
            <div className="who">agent</div>
            <div className="bubble">{stream}<span className="streaming-cursor"></span></div>
            {artifact && <ArtifactCardView artifact={artifact} onOpenFile={props.onOpenFile} />}
          </div>
        )}
        {props.turnError && (
          <div className="msg">
            <div className="bubble" style={{ borderColor: 'rgba(255,138,128,.4)' }}>
              <div className="error-text">{props.turnError.title}: {props.turnError.message}</div>
            </div>
          </div>
        )}
      </div>
      <div className="composer">
        <div className="composer-row">
          <textarea
            className="textarea"
            style={{ fontFamily: 'var(--sans)' }}
            placeholder={CHAT_MODE_PLACEHOLDER}
            value={input}
            rows={2}
            disabled={props.streaming}
            onChange={function (e) { setInput(e.target.value); }}
            onKeyDown={function (e) {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
            }}
          />
          {props.streaming
            ? <button className="btn danger" onClick={props.onCancel}>■ Stop</button>
            : <button className="btn primary" disabled={!input.trim()} onClick={send}>Send ⏎</button>}
        </div>
        <div className="hint">
          <span>Ctrl+Enter to send</span>
          <span>{BoltHarness.hasBridge() ? 'bridge: Android ✓' : 'bridge: none (browser mode)'}</span>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ files pane */

function FilesPane(props) {
  const [selected, setSelected] = useState(null);
  const [content, setContent] = useState('');
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState('');
  const viewerRef = useRef(null);

  const files = useMemo(function () {
    const list = props.files || [];
    if (!query.trim()) return list;
    const q = query.trim().toLowerCase();
    return list.filter(function (f) { return f.toLowerCase().indexOf(q) >= 0; });
  }, [props.files, query]);

  const diagFor = useCallback(function (path) {
    return (props.diagnostics[path] || []);
  }, [props.diagnostics]);

  useEffect(function () {
    if (!selected) return;
    setLoading(true);
    BoltHarness.call('readFile', selected).then(function (result) {
      setLoading(false);
      if (result.ok) { setContent(result.content); setDirty(false); }
      else { setContent(''); props.toast('Files', result.error, 'err'); }
    });
  }, [selected, props.filesRevision]);

  useEffect(function () {
    if (selected && props.diagnostics[selected] && viewerRef.current) {
      viewerRef.current.scrollTop = 0;
    }
  }, [props.diagnostics]);

  // "Open in files" requests from the Agent pane artifact cards.
  useEffect(function () {
    function onOpenFile(e) {
      if (e.detail) setSelected(e.detail);
    }
    window.addEventListener('bolt-open-file', onOpenFile);
    return function () { window.removeEventListener('bolt-open-file', onOpenFile); };
  }, []);

  async function save() {
    if (!selected || !dirty) return;
    const result = await BoltHarness.call('writeFile', selected, content);
    if (result.ok) { setDirty(false); props.toast('Files', 'Saved ' + selected, 'ok'); }
    else props.toast('Save failed', result.error, 'err');
  }

  const diagLines = useMemo(function () {
    const map = {};
    diagFor(selected).forEach(function (d) { map[d.line] = d; });
    return map;
  }, [selected, props.diagnostics]);

  const lines = content ? content.split('\n') : [];

  return (
    <div className="section">
      <div className="section-head">
        <span>Files</span>
        <input className="input" style={{ width: 200, padding: '2px 8px', fontSize: 12 }}
          placeholder="filter…" value={query} onChange={function (e) { setQuery(e.target.value); }} />
        <div className="spacer"></div>
        <span className="faint">{(props.files || []).length} file(s) · watcher live</span>
      </div>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <div className="file-list" style={{ width: 240, borderRight: '1px solid var(--border)', overflowY: 'auto', flexShrink: 0 }}>
          {files.map(function (f) {
            const d = diagFor(f);
            const errCount = d.filter(function (x) { return x.severity === 'ERROR'; }).length;
            const warnCount = d.filter(function (x) { return x.severity === 'WARNING'; }).length;
            return (
              <div key={f} className={'file-row' + (selected === f ? ' active' : '')} onClick={function () { setSelected(f); }}>
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f}</span>
                {errCount > 0 && <span className="diag err">●{errCount}</span>}
                {errCount === 0 && warnCount > 0 && <span className="diag warn">●{warnCount}</span>}
              </div>
            );
          })}
          {files.length === 0 && <div className="empty">no files</div>}
        </div>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          {selected ? (
            <React.Fragment>
              <div className="section-head" style={{ textTransform: 'none' }}>
                <span className="mono small">{selected}</span>
                <div className="spacer"></div>
                {loading && <span className="faint">loading…</span>}
                <button className="btn small primary" disabled={!dirty} onClick={save}>Save</button>
              </div>
              <div className="code-view" ref={viewerRef}>
                {lines.map(function (text, i) {
                  const d = diagLines[i + 1];
                  const cls = d ? (d.severity === 'ERROR' ? 'err' : 'warn') : '';
                  const title = d ? d.severity + ' @' + d.line + ':' + d.column + ' — ' + d.message : null;
                  return (
                    <div key={i} className={'code-line ' + cls} title={title}>
                      <span className="no">{i + 1}</span>
                      <span>{text}</span>
                    </div>
                  );
                })}
              </div>
              <div className="composer" style={{ padding: 8 }}>
                <textarea className="textarea" style={{ minHeight: 60, maxHeight: 120 }}
                  value={content} onChange={function (e) { setContent(e.target.value); setDirty(true); }}
                  placeholder="file content" />
              </div>
            </React.Fragment>
          ) : (
            <div className="empty" style={{ margin: 'auto' }}>Select a file to view and edit.</div>
          )}
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ git pane */

function GitPane(props) {
  const [status, setStatus] = useState(null);
  const [commits, setCommits] = useState([]);
  const [branches, setBranches] = useState(null);
  const [commitMsg, setCommitMsg] = useState('');
  const [diff, setDiff] = useState(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async function () {
    const [st, lg, br] = await Promise.all([
      BoltHarness.call('git', 'status', ''),
      BoltHarness.call('git', 'log', ''),
      BoltHarness.call('git', 'branches', ''),
    ]);
    if (st.ok) setStatus(st);
    else { setStatus(null); props.toast('Git', st.error, 'err'); }
    if (lg.ok) setCommits(lg.commits || []);
    if (br.ok) setBranches(br);
  }, [props.toast]);

  useEffect(function () { refresh(); }, [props.filesRevision]);

  async function op(cmd, arg, okMsg) {
    setBusy(true);
    const result = await BoltHarness.call('git', cmd, arg);
    setBusy(false);
    if (result.ok) { props.toast('Git', okMsg || (cmd + ' ok'), 'ok'); refresh(); }
    else props.toast('Git', result.error, 'err');
    return result;
  }

  async function stageAllAndCommit() {
    if (!commitMsg.trim()) { props.toast('Git', 'Commit message is empty', 'err'); return; }
    const add = await op('addAll', '', 'staged');
    if (!add.ok) return;
    const commit = await op('commit', commitMsg.trim(), 'committed');
    if (commit.ok) setCommitMsg('');
  }

  async function showDiff(path) {
    const result = await BoltHarness.call('git', 'diff', path);
    if (result.ok) setDiff({ path: path, text: result.diff });
    else props.toast('Git', result.error, 'err');
  }

  if (status === null) {
    return (
      <div className="section">
        <div className="section-head"><span>Git</span></div>
        <div className="empty">Git unavailable for this workspace (SAF projects have no local .git — app-private workspaces only).</div>
      </div>
    );
  }

  return (
    <div className="section">
      <div className="section-head">
        <span>Git</span>
        <span className="chip">{status.branch}</span>
        {status.clean ? <span className="chip ok"><span className="dot"></span>clean</span> : <span className="chip warn"><span className="dot"></span>dirty</span>}
        <div className="spacer"></div>
        <button className="btn small" onClick={refresh}>↻ refresh</button>
      </div>
      <div className="section-body">
        <div className="card">
          <div className="card-head">
            <span className="small dim">Working tree</span>
            <div className="spacer" style={{ flex: 1 }}></div>
            <button className="btn small" disabled={busy} onClick={function () { op('addAll', '', 'staged all'); }}>Stage all</button>
          </div>
          <div className="card-body">
            {status.staged.length === 0 && status.unstaged.length === 0 && status.untracked.length === 0 &&
              <div className="faint small">No changes.</div>}
            {status.staged.map(function (e) {
              return <div key={'s' + e.path} className="row small mono" style={{ marginBottom: 4 }}>
                <span className="faint" style={{ width: 72 }}>staged</span><span style={{ color: 'var(--success)' }}>{e.change}</span>
                <span className="dim">{e.path}</span>
              </div>;
            })}
            {status.unstaged.map(function (e) {
              return <div key={'u' + e.path} className="row small mono" style={{ marginBottom: 4 }}>
                <span className="faint" style={{ width: 72 }}>unstaged</span><span style={{ color: 'var(--warning)' }}>{e.change}</span>
                <span className="dim" style={{ cursor: 'pointer' }} onClick={function () { showDiff(e.path); }}>{e.path}</span>
              </div>;
            })}
            {status.untracked.map(function (p) {
              return <div key={'n' + p} className="row small mono" style={{ marginBottom: 4 }}>
                <span className="faint" style={{ width: 72 }}>untracked</span><span className="dim">{p}</span>
              </div>;
            })}
          </div>
        </div>

        <div className="card">
          <div className="card-head"><span className="small dim">Commit</span></div>
          <div className="card-body">
            <div className="row">
              <input className="input" placeholder="commit message" value={commitMsg}
                onChange={function (e) { setCommitMsg(e.target.value); }} />
              <button className="btn primary" disabled={busy || !commitMsg.trim()} onClick={stageAllAndCommit}>Commit</button>
            </div>
          </div>
        </div>

        {branches && (
          <div className="card">
            <div className="card-head"><span className="small dim">Branches & tags</span></div>
            <div className="card-body">
              <div className="row wrap">
                {(branches.branches || []).map(function (b) {
                  return <button key={b} className={'btn small' + (b === branches.current ? ' primary' : '')}
                    disabled={busy || b === branches.current}
                    onClick={function () { op('checkout', b, 'checked out ' + b); }}>{b}</button>;
                })}
                <button className="btn small ghost" disabled={busy} onClick={function () {
                  const name = prompt('New branch name:');
                  if (name) op('branch', name, 'branch created');
                }}>+ branch</button>
                <button className="btn small ghost" disabled={busy} onClick={function () {
                  const name = prompt('New tag name:');
                  if (name) op('tag', name, 'tag created');
                }}>+ tag</button>
              </div>
              {(branches.tags || []).length > 0 && (
                <div className="row wrap" style={{ marginTop: 8 }}>
                  {branches.tags.map(function (t) { return <span key={t} className="chip">{t}</span>; })}
                </div>
              )}
            </div>
          </div>
        )}

        <div className="card">
          <div className="card-head"><span className="small dim">History</span></div>
          <div className="commit-list">
            {commits.length === 0 && <div className="empty">No commits yet.</div>}
            {commits.map(function (c) {
              return (
                <div key={c.sha} className="commit-row">
                  <div className="row"><span className="sha">{c.sha.slice(0, 8)}</span><span className="when">{relTime(c.timestamp)}</span></div>
                  <div className="msg">{c.message}</div>
                </div>
              );
            })}
          </div>
        </div>

        {diff && (
          <div className="card">
            <div className="card-head">
              <span className="mono small dim">diff · {diff.path}</span>
              <div className="spacer" style={{ flex: 1 }}></div>
              <button className="btn small ghost" onClick={function () { setDiff(null); }}>close</button>
            </div>
            <div className="diff-view">
              {diff.text.split('\n').map(function (line, i) {
                const cls = line.startsWith('+') ? 'add' : line.startsWith('-') ? 'del' : line.startsWith('---') || line.startsWith('+++') ? 'hdr' : '';
                return <div key={i} className={cls}>{line}</div>;
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ terminal pane */

function TerminalPane(props) {
  const [out, setOut] = useState('');
  const [cmd, setCmd] = useState('');
  const [running, setRunning] = useState(false);
  const outRef = useRef(null);
  const uns = useRef([]);

  useEffect(function () {
    const off = BoltHarness.on('terminal_output', function (payload) {
      uns.current.push(payload.text);
      if (uns.current.length > 400) uns.current.splice(0, uns.current.length - 400);
      setOut(uns.current.join(''));
    });
    return off;
  }, []);

  useEffect(function () {
    if (outRef.current) outRef.current.scrollTop = outRef.current.scrollHeight;
  }, [out]);

  async function start() {
    const result = await BoltHarness.call('terminalStart');
    if (result.ok) setRunning(true);
    else props.toast('Terminal', result.error, 'err');
  }

  async function stop() {
    await BoltHarness.call('terminalStop');
    setRunning(false);
  }

  async function send() {
    if (!cmd.trim()) return;
    const result = await BoltHarness.call('terminalSend', cmd);
    if (result.ok) setCmd('');
    else props.toast('Terminal', result.error, 'err');
  }

  return (
    <div className="section">
      <div className="section-head">
        <span>Terminal</span>
        {running ? <span className="chip ok"><span className="dot"></span>sh</span> : <span className="chip">stopped</span>}
        <div className="spacer"></div>
        {running
          ? <button className="btn small danger" onClick={stop}>Stop</button>
          : <button className="btn small" onClick={start}>Start session</button>}
      </div>
      <div className="terminal">
        <div className="terminal-out" ref={outRef}>
          {out || (running ? '' : 'Session stopped. Press Start to spawn /system/bin/sh in the project root.\n\nAI-originated commands pass the durable approval policy; this pane is the user terminal.')}
        </div>
        <div className="terminal-in">
          <span className="prompt">$</span>
          <input value={cmd} disabled={!running}
            placeholder="command…"
            onChange={function (e) { setCmd(e.target.value); }}
            onKeyDown={function (e) { if (e.key === 'Enter') send(); }} />
          <button className="btn small" disabled={!running || !cmd.trim()} onClick={send}>↵</button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ skills pane */

function SkillsPane(props) {
  const [skills, setSkills] = useState([]);
  const [gitUrl, setGitUrl] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async function () {
    const result = await BoltHarness.call('skills');
    if (result.ok) setSkills(result.skills || []);
  }, []);

  useEffect(function () {
    refresh();
    const off1 = BoltHarness.on('skill_installed', function () { refresh(); });
    const off2 = BoltHarness.on('skill_install_error', function (p) { props.toast('Skill install', p.message, 'err'); });
    return function () { off1(); off2(); };
  }, []);

  async function toggle(skill) {
    const result = await BoltHarness.call('setSkillEnabled', skill.id, !skill.enabled);
    if (result.ok) refresh();
    else props.toast('Skills', result.error, 'err');
  }

  async function remove(skill) {
    if (!confirm('Remove skill "' + skill.name + '"?')) return;
    const result = await BoltHarness.call('removeSkill', skill.id);
    if (result.ok) refresh();
    else props.toast('Skills', result.error, 'err');
  }

  async function install() {
    if (!gitUrl.trim()) return;
    setBusy(true);
    const result = await BoltHarness.call('installSkillFromGit', gitUrl.trim());
    setBusy(false);
    if (result.ok) { props.toast('Skills', 'Downloading ' + gitUrl + ' …', 'ok'); setGitUrl(''); }
    else props.toast('Skills', result.error, 'err');
  }

  return (
    <div className="section">
      <div className="section-head"><span>Skills</span><div className="spacer"></div><span className="faint">{skills.length} installed</span></div>
      <div className="section-body">
        <div className="card">
          <div className="card-head"><span className="small dim">Install from git</span></div>
          <div className="card-body">
            <div className="row">
              <input className="input mono" style={{ fontSize: 12 }} placeholder="https://github.com/owner/skill-repo"
                value={gitUrl} onChange={function (e) { setGitUrl(e.target.value); }} />
              <button className="btn primary" disabled={busy || !gitUrl.trim()} onClick={install}>Install</button>
            </div>
            <div className="faint small" style={{ marginTop: 6 }}>
              Public HTTPS GitHub repositories only. The tarball is bounded and validated (plugin.json, no executable entrypoints).
              ZIP installs are available from the app's Agent Center.
            </div>
          </div>
        </div>
        <div className="card">
          {skills.length === 0 && <div className="empty">No skills installed.</div>}
          {skills.map(function (s) {
            return (
              <div key={s.id} className="skill-row">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="skill-name">{s.name}</div>
                  <div className="skill-desc">{s.description}</div>
                  <div className="skill-src">{s.id} @ {s.version} · {s.source}</div>
                </div>
                <span className={'chip ' + (s.enabled ? 'ok' : '')}>{s.enabled ? 'enabled' : 'disabled'}</span>
                <button className="btn small" disabled={s.builtIn && s.enabled} onClick={function () { toggle(s); }}>{s.enabled ? 'Disable' : 'Enable'}</button>
                {!s.builtIn && <button className="btn small danger" onClick={function () { remove(s); }}>Delete</button>}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ MCP pane */

function McpPane(props) {
  const [servers, setServers] = useState([]);
  const [tools, setTools] = useState(null);
  const [form, setForm] = useState({ id: '', name: '', url: '', apiKey: '' });
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async function () {
    const result = await BoltHarness.call('mcpServers');
    if (result.ok) setServers(result.servers || []);
  }, []);

  useEffect(function () { refresh(); }, []);

  async function add() {
    if (!form.id.trim() || !form.url.trim()) { props.toast('MCP', 'id and URL are required', 'err'); return; }
    const config = { id: form.id.trim(), name: form.name.trim() || form.id.trim(), url: form.url.trim(), apiKey: form.apiKey || null, enabled: true };
    const result = await BoltHarness.call('mcpAdd', JSON.stringify(config));
    if (result.ok) { setForm({ id: '', name: '', url: '', apiKey: '' }); refresh(); props.toast('MCP', 'Server saved', 'ok'); }
    else props.toast('MCP', result.error, 'err');
  }

  async function listTools(server) {
    setBusy(true);
    const result = await BoltHarness.call('mcpTools', server.id);
    setBusy(false);
    if (result.ok) setTools({ server: server, list: result.tools || [] });
    else props.toast('MCP', result.error, 'err');
  }

  async function callTool(server, tool) {
    let args = '{}';
    try { args = prompt('Arguments JSON for ' + tool.name + ':', '{}') || '{}'; JSON.parse(args); }
    catch (e) { props.toast('MCP', 'Invalid JSON arguments', 'err'); return; }
    const result = await BoltHarness.call('mcpCall', server.id, tool.name, args);
    if (result.ok) props.toast(tool.name, result.text.slice(0, 400), result.success ? 'ok' : 'err');
    else props.toast('MCP', result.error, 'err');
  }

  return (
    <div className="section">
      <div className="section-head"><span>MCP</span><div className="spacer"></div><span className="faint">streamable HTTP · JSON-RPC 2.0</span></div>
      <div className="section-body">
        <div className="card">
          <div className="card-head"><span className="small dim">Add server</span></div>
          <div className="card-body">
            <div className="row" style={{ marginBottom: 8 }}>
              <input className="input mono" style={{ flex: 1 }} placeholder="id (slug)" value={form.id}
                onChange={function (e) { setForm(Object.assign({}, form, { id: e.target.value })); }} />
              <input className="input" style={{ flex: 1 }} placeholder="name" value={form.name}
                onChange={function (e) { setForm(Object.assign({}, form, { name: e.target.value })); }} />
            </div>
            <div className="row" style={{ marginBottom: 8 }}>
              <input className="input mono" style={{ flex: 2, fontSize: 12 }} placeholder="https://server.example.com/mcp" value={form.url}
                onChange={function (e) { setForm(Object.assign({}, form, { url: e.target.value })); }} />
              <input className="input mono" style={{ flex: 1, fontSize: 12 }} type="password" placeholder="API key (Keystore-encrypted)" value={form.apiKey}
                onChange={function (e) { setForm(Object.assign({}, form, { apiKey: e.target.value })); }} />
              <button className="btn primary" onClick={add}>Add</button>
            </div>
            <div className="faint small">Remote HTTPS endpoints (loopback HTTP allowed for local testing). stdio servers are not supported on Android.</div>
          </div>
        </div>
        <div className="card">
          <div className="card-head"><span className="small dim">Servers</span></div>
          {servers.length === 0 && <div className="empty">No MCP servers configured.</div>}
          {servers.map(function (s) {
            return (
              <div key={s.id} className="skill-row">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="skill-name">{s.name}</div>
                  <div className="skill-src">{s.url}{s.hasKey ? ' · key' : ''}</div>
                </div>
                <button className="btn small" disabled={busy} onClick={function () { listTools(s); }}>Tools</button>
                <button className="btn small danger" onClick={async function () {
                  await BoltHarness.call('mcpRemove', s.id); refresh(); setTools(null);
                }}>Remove</button>
              </div>
            );
          })}
        </div>
        {tools && (
          <div className="card">
            <div className="card-head">
              <span className="small dim">Tools · {tools.server.name} ({tools.list.length})</span>
              <div className="spacer" style={{ flex: 1 }}></div>
              <button className="btn small ghost" onClick={function () { setTools(null); }}>close</button>
            </div>
            {tools.list.map(function (t) {
              return (
                <div key={t.name} className="skill-row">
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="skill-name mono" style={{ fontSize: 12 }}>{t.name}</div>
                    <div className="skill-desc">{t.description}</div>
                  </div>
                  <button className="btn small" onClick={function () { callTool(tools.server, t); }}>Invoke</button>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ build pane */

function BuildPane(props) {
  const [status, setStatus] = useState(null);
  const [logs, setLogs] = useState([]);
  const [variant, setVariant] = useState('debug');
  const [running, setRunning] = useState(false);
  const logRef = useRef(null);

  const refresh = useCallback(async function () {
    const result = await BoltHarness.call('buildStatus');
    if (result.ok) setStatus(result);
  }, []);

  useEffect(function () { refresh(); }, []);

  useEffect(function () {
    const offLog = BoltHarness.on('build_log', function (p) {
      setLogs(function (l) { return l.concat([p.line]).slice(-2000); });
    });
    const offEnd = BoltHarness.on('build_end', function (p) {
      setRunning(false);
      props.toast('Build', p.success ? 'APK ready: ' + p.apk + ' (' + fmtBytes(p.bytes) + ')' : 'failed: ' + p.error, p.success ? 'ok' : 'err');
      refresh();
    });
    return function () { offLog(); offEnd(); };
  }, []);

  useEffect(function () {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [logs.length]);

  async function start() {
    setLogs([]);
    setRunning(true);
    const result = await BoltHarness.call('buildStart', variant);
    if (!result.ok) { setRunning(false); props.toast('Build', result.error, 'err'); }
  }

  return (
    <div className="section">
      <div className="section-head">
        <span>Build APK</span>
        {status && (status.valid
          ? <span className="chip ok"><span className="dot"></span>toolchain ready</span>
          : <span className="chip warn"><span className="dot"></span>toolchain missing</span>)}
        <div className="spacer"></div>
        <button className="btn small" onClick={refresh}>↻</button>
      </div>
      <div className="section-body" style={{ display: 'flex', flexDirection: 'column', paddingBottom: 8 }}>
        <div className="card">
          <div className="card-body">
            {status && status.valid && <div className="small dim" style={{ marginBottom: 8 }}>
              JDK {status.jdk || '?'} · Gradle {status.gradle || '?'} · {status.abi || '?'}
            </div>}
            {status && !status.valid && <div className="small dim" style={{ marginBottom: 8 }}>
              {(status.problems || []).join(' · ') || 'Import an Android toolchain bundle on the Build screen first.'}
            </div>}
            <div className="row wrap">
              <button className={'btn small' + (variant === 'debug' ? ' primary' : '')} onClick={function () { setVariant('debug'); }}>debug</button>
              <button className={'btn small' + (variant === 'release' ? ' primary' : '')} onClick={function () { setVariant('release'); }}>release</button>
              <div style={{ flex: 1 }}></div>
              {running
                ? <button className="btn small danger" onClick={function () { BoltHarness.call('buildCancel'); }}>Cancel</button>
                : <button className="btn primary" disabled={!status || !status.valid} onClick={start}>▶ Build APK</button>}
            </div>
          </div>
        </div>
        {logs.length > 0 && (
          <div style={{ flex: 1, minHeight: 160, display: 'flex', flexDirection: 'column', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', overflow: 'hidden' }}>
            <div className="section-head" style={{ borderBottom: '1px solid var(--border)' }}>Gradle console</div>
            <div className="log-view" ref={logRef}>
              {logs.map(function (line, i) {
                const cls = /FAILURE|error:/i.test(line) ? 'ln-err' : (line.startsWith('$') ? 'ln-cmd' : '');
                return <div key={i} className={cls}>{line}</div>;
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ diagnostics pane */

function DiagnosticsPane(props) {
  const paths = Object.keys(props.diagnostics);
  return (
    <div className="section">
      <div className="section-head"><span>Diagnostics</span><div className="spacer"></div><span className="faint">bolt-lite · live from the file watcher</span></div>
      <div className="section-body">
        {paths.length === 0 && <div className="empty">No diagnostics. Edit files in the Files pane — the watcher analyzes them on change.</div>}
        {paths.map(function (path) {
          const items = props.diagnostics[path];
          return (
            <div key={path} className="card">
              <div className="card-head">
                <span className="mono small">{path}</span>
                <div className="spacer" style={{ flex: 1 }}></div>
                <span className="chip">{items.length} finding(s)</span>
              </div>
              <div className="card-body" style={{ paddingTop: 8, paddingBottom: 8 }}>
                {items.map(function (d, i) {
                  return (
                    <div key={i} className="row small" style={{ marginBottom: 4 }}>
                      <span className={'chip ' + (d.severity === 'ERROR' ? 'err' : d.severity === 'WARNING' ? 'warn' : '')}>{d.severity}</span>
                      <span className="mono faint">{d.line}:{d.column}</span>
                      <span className="dim">{d.message}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ toasts */

function Toasts(props) {
  return (
    <div className="toast-wrap">
      {props.toasts.map(function (t) {
        return (
          <div key={t.id} className={'toast ' + t.kind}>
            <div className="t-title">{t.title}</div>
            <div>{t.message}</div>
          </div>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ root app */

function App() {
  const [pane, setPane] = useState('agent');
  const [state, setState] = useState({ hasProvider: false, projectName: '', projectType: 'UNKNOWN', toolchain: false });
  const [files, setFiles] = useState([]);
  const [filesRevision, setFilesRevision] = useState(0);
  const [diagnostics, setDiagnostics] = useState({});
  const [messages, setMessages] = useState([]);
  const [stream, setStream] = useState('');
  const [artifact, setArtifact] = useState(null);
  const [streaming, setStreaming] = useState(false);
  const [turnError, setTurnError] = useState(null);
  const { toasts, push: toast } = useToasts();

  const refreshFiles = useCallback(async function () {
    const result = await BoltHarness.call('listFiles');
    if (result.ok) setFiles(result.files || []);
  }, []);

  const refreshMessages = useCallback(async function () {
    const result = await BoltHarness.call('messages');
    if (result.ok) setMessages(result.messages || []);
  }, []);

  // initial load
  useEffect(function () {
    BoltHarness.call('state').then(function (r) { if (r.ok) setState(r); });
    refreshFiles();
    refreshMessages();
    BoltHarness.call('analyzeFile', 'index.html').then(function () { /* seed diagnostics */ });
  }, []);

  // event pump
  useEffect(function () {
    const offs = [
      BoltHarness.on('delta', function (p) { setStream(p.text || ''); }),
      BoltHarness.on('turn_start', function () { setStreaming(true); setStream(''); setArtifact(null); setTurnError(null); }),
      BoltHarness.on('artifact', function (p) { setArtifact(p); }),
      BoltHarness.on('turn_end', function (p) {
        setStreaming(false); setStream(''); setArtifact(null);
        setTurnError(p.error || (p.engineError ? { title: 'Engine', message: p.engineError } : null));
        refreshMessages();
        refreshFiles();
      }),
      BoltHarness.on('files_changed', function () { refreshFiles(); }),
      BoltHarness.on('file_event', function () { refreshFiles(); }),
      BoltHarness.on('diagnostics', function (p) {
        setDiagnostics(function (prev) {
          const next = Object.assign({}, prev);
          next[p.path] = p.items || [];
          return next;
        });
      }),
      BoltHarness.on('zip_saved', function (p) {
        toast('ZIP saved', p.fileName + ' → Downloads', 'ok');
      }),
      BoltHarness.on('zip_error', function (p) {
        toast('ZIP failed', p.message, 'err');
      }),
    ];
    return function () { offs.forEach(function (off) { off(); }); };
  }, []);

  function sendPrompt(text) {
    // returns synchronously with an error string if rejected (AgentPane toasts it)
    let error = null;
    BoltHarness.call('sendPrompt', text).then(function (r) {
      if (!r.ok) {
        error = r.error;
        setTurnError({ title: 'Agent', message: r.error });
      }
    });
    return { get error() { return error; } };
  }

  function cancelStream() { BoltHarness.call('cancelStream'); }

  async function revertLast() {
    if (!artifact) return;
    const paths = artifact.actions.map(function (a) { return a.filePath; }).filter(Boolean);
    for (const p of paths) {
      const r = await BoltHarness.call('revertChange', p);
      if (!r.ok) toast('Revert', p + ': ' + r.error, 'err');
    }
    refreshFiles();
    toast('Revert', 'Reverted ' + paths.length + ' change(s)', 'ok');
  }

  function openFile(path) {
    setPane('files');
    // FilesPane selects on click; a selection hint is passed through a global event.
    window.dispatchEvent(new CustomEvent('bolt-open-file', { detail: path }));
  }

  return (
    <div className="app">
      <TopBar state={state} toast={toast} />
      <div className="body">
        <Rail active={pane} onSelect={setPane} />
        <main className="pane">
          {pane === 'agent' && (
            <AgentPane
              messages={messages} stream={stream} artifact={artifact} streaming={streaming}
              turnError={turnError}
              onSend={sendPrompt} onCancel={cancelStream} onRevert={revertLast}
              onOpenFile={openFile} toast={toast}
            />
          )}
          {pane === 'files' && <FilesPane files={files} filesRevision={filesRevision} diagnostics={diagnostics} toast={toast} />}
          {pane === 'git' && <GitPane toast={toast} filesRevision={filesRevision} />}
          {pane === 'terminal' && <TerminalPane toast={toast} />}
          {pane === 'skills' && <SkillsPane toast={toast} />}
          {pane === 'mcp' && <McpPane toast={toast} />}
          {pane === 'build' && <BuildPane toast={toast} />}
          {pane === 'diag' && <DiagnosticsPane diagnostics={diagnostics} />}
        </main>
      </div>
      <Toasts toasts={toasts} />
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
