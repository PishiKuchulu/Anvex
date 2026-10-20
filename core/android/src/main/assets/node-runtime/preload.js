'use strict';
/*
 * Anvex Node runtime preload (loaded with `node -r` before every command).
 *
 * Jobs:
 *  1. chdir() into the project workspace (ANVEX_CWD) — node::Start inherits
 *     the app process cwd, which is meaningless for project work.
 *  2. Forward process.stdout / process.stderr to the app's NodeManager over
 *     a localhost socket (ANVEX_NODE_PORT, first line = ANVEX_NODE_TOKEN), so
 *     the terminal shows live output. nodejs-mobile also mirrors stdio to
 *     logcat; that stays untouched.
 *
 * Failure here must NEVER break the user's command: every step is guarded,
 * and socket errors are swallowed (an EPIPE must not crash a build).
 */
(function () {
  try {
    var cwd = process.env.ANVEX_CWD;
    if (cwd) {
      try { process.chdir(cwd); } catch (e) { /* keep default cwd */ }
    }

    var port = parseInt(process.env.ANVEX_NODE_PORT || '0', 10);
    if (!port) return;
    var token = process.env.ANVEX_NODE_TOKEN || '';

    var net = require('net');
    var sock = net.connect(port, '127.0.0.1');
    var ready = false;
    var pending = [];

    sock.on('connect', function () {
      try { sock.write(token + '\n'); } catch (e) {}
      ready = true;
      for (var i = 0; i < pending.length; i++) {
        try { sock.write(pending[i]); } catch (e) {}
      }
      pending = [];
    });
    sock.on('error', function () { /* output falls back to logcat only */ });
    sock.on('close', function () { ready = false; });

    function forward(chunk, encoding) {
      try {
        var buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk), typeof encoding === 'string' ? encoding : 'utf8');
        if (ready) { sock.write(buf); } else { pending.push(buf); if (pending.length > 512) pending.shift(); }
      } catch (e) { /* never throw into user code */ }
    }

    function wrap(stream) {
      if (!stream || typeof stream.write !== 'function' || stream.__anvexWrapped) return;
      var orig = stream.write;
      stream.write = function (chunk, encoding, callback) {
        forward(chunk, encoding);
        return orig.apply(stream, arguments);
      };
      stream.__anvexWrapped = true;
    }

    wrap(process.stdout);
    wrap(process.stderr);

    // Keep the socket from holding a one-shot command's process open after
    // the main script finished: unref so Node can exit naturally.
    sock.unref();
  } catch (e) { /* preload must never break the command */ }
})();
