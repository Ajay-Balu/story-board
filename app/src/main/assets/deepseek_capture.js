/*
 * Story-Board capture script for chat.deepseek.com
 *
 * Injected at document start. It wraps window.fetch (and XHR as a fallback) so that the
 * streamed reply of every chat completion is copied to the app AS IT ARRIVES. Because the copy
 * is taken from the network stream, a reply that the site later erases (guardrail) is still kept.
 *
 * A DOM watcher runs as a second safety net: it remembers the longest text ever shown in the
 * latest assistant message and notices when that text is suddenly replaced.
 *
 * Bridge (Android): window.StoryBoardBridge.onStart(id, prompt)
 *                   window.StoryBoardBridge.onUpdate(id, text)
 *                   window.StoryBoardBridge.onEnd(id, text, metaJson)
 */
(function () {
  if (window.__storyBoardCapture) return;
  window.__storyBoardCapture = true;

  var COMPLETION_RE = /\/api\/v\d+\/chat\/(completion|regenerate|edit_message|continue|resume_stream)/;
  // Any other chat API call that turns out to stream a reply is captured too (future endpoints).
  var CHAT_API_RE = /\/api\/v\d+\/chat\/(?!tts|history_messages|create_pow_challenge|stop_stream|message_feedback)/;
  var FILTER_WORDS = /CONTENT_FILTER|content_filter|FILTERED|INCOMPLETE_FILTER|BANNED/;

  function bridge() { return window.StoryBoardBridge; }
  function log() { try { console.log.apply(console, ['[StoryBoard]'].concat([].slice.call(arguments))); } catch (e) {} }

  var seq = 0;
  function newId() { seq += 1; return 'ds-' + Date.now() + '-' + seq; }

  // ---------------------------------------------------------------- stream parser
  /*
   * Handles the formats DeepSeek has used:
   *  A) OpenAI-like:  {"choices":[{"delta":{"content":"..","type":"text"|"thinking"}}]}
   *  B) JSON-patch:   {"v":{"response":{"fragments":[{"type":"RESPONSE","content":".."}]}}}
   *                   {"p":"response/fragments/-1/content","o":"APPEND","v":".."}
   *                   {"v":".."}                       (append to the last path)
   *                   {"p":"response/fragments","o":"APPEND","v":[{"type":"THINK","content":".."}]}
   *                   {"p":"response","o":"BATCH","v":[{"p":"status","v":"FINISHED"}, ...]}
   */
  function Parser() {
    this.fragments = [];      // [{type, content}]
    this.plain = '';          // text from format A (answer only)
    this.lastPath = null;
    this.filtered = false;
    this.status = null;
  }

  Parser.prototype.text = function () {
    var out = '';
    var hasTyped = false;
    for (var i = 0; i < this.fragments.length; i++) {
      var f = this.fragments[i];
      var t = String(f.type || '').toUpperCase();
      if (t) hasTyped = true;
      if (t === 'THINK' || t === 'THINKING' || t === 'SEARCH' || t === 'TOOL_SEARCH' || t === 'TIP') continue;
      out += f.content || '';
    }
    if (!out && !hasTyped) {
      for (var j = 0; j < this.fragments.length; j++) out += this.fragments[j].content || '';
    }
    return (this.plain + out);
  };

  Parser.prototype.ensureFragment = function () {
    if (!this.fragments.length) this.fragments.push({ type: 'RESPONSE', content: '' });
    return this.fragments[this.fragments.length - 1];
  };

  Parser.prototype.fragmentAt = function (idxStr) {
    var idx = parseInt(idxStr, 10);
    if (isNaN(idx)) return this.ensureFragment();
    if (idx < 0) idx = this.fragments.length + idx;
    while (this.fragments.length <= idx) this.fragments.push({ type: 'RESPONSE', content: '' });
    return this.fragments[idx < 0 ? 0 : idx];
  };

  Parser.prototype.applyResponseObject = function (resp) {
    if (!resp || typeof resp !== 'object') return;
    if (Array.isArray(resp.fragments)) {
      this.fragments = resp.fragments.map(function (f) { return { type: f.type, content: f.content || '' }; });
    }
    if (resp.status) this.setStatus(resp.status);
  };

  Parser.prototype.setStatus = function (s) {
    this.status = s;
    if (FILTER_WORDS.test(String(s))) this.filtered = true;
  };

  Parser.prototype.applyOp = function (path, op, value) {
    if (path == null) path = this.lastPath;
    else this.lastPath = path;
    op = (op || (typeof value === 'string' && path ? 'APPEND' : 'SET')).toUpperCase();

    if (op === 'BATCH' && Array.isArray(value)) {
      var base = path || '';
      for (var i = 0; i < value.length; i++) {
        var sub = value[i] || {};
        var subPath = sub.p == null ? null : (base ? base + '/' + sub.p : sub.p);
        this.applyOp(subPath, sub.o, sub.v);
      }
      this.lastPath = path;
      return;
    }

    if (path == null) {
      // {"v": {...}} initial object, or bare string without any known path
      if (value && typeof value === 'object' && value.response) this.applyResponseObject(value.response);
      else if (typeof value === 'string') this.ensureFragment().content += value;
      return;
    }

    var parts = String(path).split('/');
    var last = parts[parts.length - 1];

    if (/status$/.test(path) || /quasi_status$/.test(path)) { this.setStatus(value); return; }
    if (/fragments\/-?\d+\/content$/.test(path)) {
      var f = this.fragmentAt(parts[parts.length - 2]);
      if (op === 'APPEND') f.content += (value == null ? '' : String(value));
      else f.content = (value == null ? '' : String(value));
      return;
    }
    if (/fragments$/.test(path)) {
      var arr = Array.isArray(value) ? value : [value];
      if (op === 'SET') this.fragments = [];
      for (var k = 0; k < arr.length; k++) {
        if (arr[k] && typeof arr[k] === 'object') this.fragments.push({ type: arr[k].type, content: arr[k].content || '' });
      }
      return;
    }
    if (path === 'response' && value && typeof value === 'object' && !Array.isArray(value)) {
      this.applyResponseObject(value);
      return;
    }
    if (last === 'content' && typeof value === 'string') {
      // older style "response/content"
      var tf = this.ensureFragment();
      if (op === 'APPEND') tf.content += value; else tf.content = value;
      return;
    }
    if (/thinking_content$/.test(path)) return; // ignore reasoning text
  };

  Parser.prototype.feedEvent = function (dataStr) {
    if (!dataStr || dataStr === '[DONE]') return;
    var obj;
    try { obj = JSON.parse(dataStr); } catch (e) { return; }
    if (!obj || typeof obj !== 'object') return;

    // Format A
    if (Array.isArray(obj.choices)) {
      for (var i = 0; i < obj.choices.length; i++) {
        var ch = obj.choices[i] || {};
        var d = ch.delta || {};
        if (typeof d.content === 'string' && d.type !== 'thinking') this.plain += d.content;
        if (ch.finish_reason && FILTER_WORDS.test(String(ch.finish_reason))) this.filtered = true;
      }
      return;
    }
    // Explicit error / filter events
    if (obj.type === 'error' || obj.code && FILTER_WORDS.test(String(obj.code))) this.filtered = true;
    if (obj.finish_reason && FILTER_WORDS.test(String(obj.finish_reason))) this.filtered = true;

    // Format B
    if ('v' in obj || 'p' in obj) this.applyOp(obj.p == null ? null : obj.p, obj.o, obj.v);
  };

  /** Splits a growing SSE text buffer into complete events. */
  function SseReader(onEvent) {
    this.buf = '';
    this.onEvent = onEvent;
  }
  SseReader.prototype.push = function (chunk) {
    this.buf += chunk;
    var idx;
    while ((idx = this.buf.search(/\r?\n\r?\n/)) >= 0) {
      var block = this.buf.slice(0, idx);
      var m = this.buf.slice(idx).match(/^\r?\n\r?\n/);
      this.buf = this.buf.slice(idx + (m ? m[0].length : 2));
      this.handleBlock(block);
    }
  };
  SseReader.prototype.flush = function () {
    if (this.buf.trim()) this.handleBlock(this.buf);
    this.buf = '';
  };
  SseReader.prototype.handleBlock = function (block) {
    var lines = block.split(/\r?\n/);
    var data = [];
    var eventName = null;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i];
      if (l.indexOf('data:') === 0) data.push(l.slice(5).replace(/^ /, ''));
      else if (l.indexOf('event:') === 0) eventName = l.slice(6).trim();
      else if (l.charAt(0) === '{') data.push(l); // NDJSON fallback
    }
    if (data.length) this.onEvent(data.join('\n'), eventName);
  };

  // ---------------------------------------------------------------- replies
  /*
   * One Reply per assistant message. A long reply can arrive over several HTTP streams: the first
   * /completion stream, then /resume_stream or /continue after DeepSeek's page reconnects. All of them
   * feed the same Reply. When no stream is active any more, the final, complete message is read from
   * DeepSeek's own chat history (the authoritative copy). Only if DeepSeek erased it (guardrail) do we
   * fall back to the longest text seen while it streamed.
   */
  var current = null;

  function Reply(prompt, sessionId) {
    this.id = newId();
    this.prompt = prompt || '';
    this.sessionId = sessionId || null;
    this.msgId = null;
    this.best = '';
    this.active = 0;
    this.streams = 0;
    this.filtered = false;
    this.status = null;
    this.finished = false;
    this.timer = null;
    this.lastSent = 0;
    this.lastText = '';
    try { bridge() && bridge().onStart(this.id, this.prompt); } catch (e) { log('onStart failed', e); }
    DomWatch.begin(this.id);
  }

  Reply.prototype.update = function (text, force) {
    if (text && text.length > this.best.length) this.best = text;
    var now = Date.now();
    if (this.best === this.lastText) return;
    if (!force && now - this.lastSent < 400) return;
    this.lastSent = now;
    this.lastText = this.best;
    try { bridge() && bridge().onUpdate(this.id, this.best); } catch (e) {}
  };

  Reply.prototype.streamStarted = function () {
    this.active += 1;
    this.streams += 1;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
  };

  Reply.prototype.streamEnded = function (parser, reason) {
    this.active = Math.max(0, this.active - 1);
    if (parser.filtered) this.filtered = true;
    if (parser.status) this.status = parser.status;
    this.update(parser.text(), true);
    log('stream ended (' + reason + '), streams=' + this.streams + ' active=' + this.active + ' best=' + this.best.length);
    if (this.active === 0) this.scheduleFinish(2500);
  };

  Reply.prototype.scheduleFinish = function (ms) {
    var self = this;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(function () { self.finish(); }, ms);
  };

  function authToken() {
    try {
      var raw = localStorage.getItem('userToken');
      var o = JSON.parse(raw);
      return (o && (o.value || o.token)) || raw;
    } catch (e) { return localStorage.getItem('userToken'); }
  }

  function fragmentsText(msg) {
    var frags = msg.fragments || [];
    var out = '';
    for (var i = 0; i < frags.length; i++) {
      var t = String(frags[i].type || '').toUpperCase();
      if (t && t !== 'RESPONSE' && t !== 'TEXT' && t !== 'ANSWER') continue;
      out += frags[i].content || '';
    }
    if (!out && typeof msg.content === 'string') out = msg.content;
    return out;
  }

  /** Reads the assistant message from DeepSeek's history. Resolves {status, text} or null. */
  function fetchFinal(reply) {
    var sid = reply.sessionId;
    if (!sid) { var m = location.pathname.match(/\/a\/chat\/s\/([\w-]+)/); if (m) sid = m[1]; }
    if (!sid) return Promise.resolve(null);
    var headers = { 'x-client-platform': 'web' };
    var tok = authToken(); if (tok) headers.authorization = 'Bearer ' + tok;
    return origFetch.call(window, '/api/v0/chat/history_messages?chat_session_id=' + encodeURIComponent(sid), { headers: headers, credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var msgs = (j && j.data && j.data.biz_data && j.data.biz_data.chat_messages) || [];
        var msg = null;
        for (var i = 0; i < msgs.length; i++) {
          if (reply.msgId != null && msgs[i].message_id === reply.msgId) msg = msgs[i];
        }
        if (!msg) msg = msgs.filter(function (x) { return x.role === 'ASSISTANT'; }).pop();
        if (!msg) return null;
        return { status: msg.status, text: fragmentsText(msg) };
      })
      .catch(function (e) { log('history fetch failed ' + e); return null; });
  }

  Reply.prototype.finish = function () {
    if (this.finished || this.active > 0) return;
    var self = this;
    var deadline = Date.now() + 6 * 60 * 1000;
    (function poll() {
      if (self.active > 0) return;             // a resume stream started; it will finish later
      fetchFinal(self).then(function (h) {
        if (self.active > 0 || self.finished) return;
        var st = h ? String(h.status || '') : '';
        if (h && (st === 'WIP' || st === 'PENDING') && Date.now() < deadline) {
          if (h.text) self.update(h.text, false);
          setTimeout(poll, 3000);
          return;
        }
        self.finished = true;
        var dom = DomWatch.finish(self.id);
        var streamed = (self.best && self.best.length >= (dom.maxText || '').length * 0.6) ? self.best : (dom.maxText || self.best);
        var historyFiltered = FILTER_WORDS.test(st);
        var text, source;
        if (h && h.text && !historyFiltered && h.text.length >= streamed.length * 0.8) { text = h.text; source = 'history'; }
        else { text = streamed || (h && h.text) || ''; source = 'stream'; }
        var erased = self.filtered || historyFiltered || (source === 'stream' && dom.erased);
        var meta = {
          erased: erased, filtered: self.filtered, status: st || self.status, source: source,
          streams: self.streams, historyLength: h && h.text ? h.text.length : -1,
          streamedLength: self.best.length, domMaxLength: (dom.maxText || '').length
        };
        log('reply finished ' + JSON.stringify(meta));
        try { bridge() && bridge().onEnd(self.id, text, JSON.stringify(meta)); } catch (e) { log('onEnd failed', e); }
      });
    })();
  };

  function bodyJson(body) {
    try { if (typeof body === 'string') return JSON.parse(body) || {}; } catch (e) {}
    return {};
  }

  /** Called for every completion-type request; returns the Reply that the stream belongs to. */
  function replyFor(url, body) {
    var o = bodyJson(body);
    var isNew = !/\/chat\/(continue|resume_stream)/.test(url);   // completion, regenerate, edit_message ... start a new reply
    if (!isNew && current && (!current.finished || Date.now() - current.lastSent < 120000)) {
      if (current.finished) { current.finished = false; log('reopening reply after late resume'); }
      return current;
    }
    current = new Reply(o.prompt || o.message || '', o.chat_session_id);
    if (o.message_id != null && !isNew) current.msgId = o.message_id;
    return current;
  }

  /** Feeds one HTTP stream (text chunks) into a reply. */
  function StreamFeed(reply) {
    var parser = new Parser();
    this.parser = parser;
    this.reply = reply;
    this.sse = new SseReader(function (data, ev) {
      if (ev === 'ready') {
        try { var r = JSON.parse(data); if (r.response_message_id != null) reply.msgId = r.response_message_id; } catch (e) {}
        return;
      }
      if (ev && FILTER_WORDS.test(ev)) parser.filtered = true;
      parser.feedEvent(data);
      reply.update(parser.text(), false);
    });
    reply.streamStarted();
  }
  StreamFeed.prototype.push = function (chunk) { this.sse.push(chunk); };
  StreamFeed.prototype.done = function (reason) { this.sse.flush(); this.reply.streamEnded(this.parser, reason); };

  // ---------------------------------------------------------------- fetch hook
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var p = origFetch.apply(this, arguments);
      var known = COMPLETION_RE.test(url);
      if (!known && !CHAT_API_RE.test(url)) return p;
      var body = init && init.body;
      return p.then(function (resp) {
        try {
          if (!resp.body || !resp.body.tee) return resp;
          if (!known && !/event-stream/.test(resp.headers.get('content-type') || '')) return resp;
          if (!known) log('capturing stream from ' + url);
          var pair = resp.body.tee();
          var feed = new StreamFeed(replyFor(url, body));
          readStream(pair[1], feed);
          return new Response(pair[0], { status: resp.status, statusText: resp.statusText, headers: resp.headers });
        } catch (e) {
          log('tee failed', e);
          return resp;
        }
      });
    };
  }

  function readStream(stream, feed) {
    var reader = stream.getReader();
    var decoder = new TextDecoder('utf-8');
    function pump() {
      return reader.read().then(function (r) {
        if (r.done) { feed.push(decoder.decode()); feed.done('done'); return; }
        feed.push(decoder.decode(r.value, { stream: true }));
        return pump();
      }).catch(function (e) { feed.done('aborted'); });
    }
    pump();
  }

  // ---------------------------------------------------------------- XHR hook (fallback)
  var XO = XMLHttpRequest.prototype.open;
  var XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__sbUrl = url;
    return XO.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (this.__sbUrl && COMPLETION_RE.test(String(this.__sbUrl))) {
      var feed = new StreamFeed(replyFor(String(this.__sbUrl), body));
      var seen = 0;
      var xhr = this;
      xhr.addEventListener('progress', function () {
        var t = xhr.responseText || '';
        feed.push(t.slice(seen)); seen = t.length;
      });
      xhr.addEventListener('loadend', function () {
        var t = xhr.responseText || '';
        feed.push(t.slice(seen)); seen = t.length;
        feed.done('done');
      });
    }
    return XS.apply(this, arguments);
  };

  // ---------------------------------------------------------------- DOM watcher
  var DomWatch = (function () {
    var SEL = '.ds-markdown, [class*="markdown"]';
    var state = {};
    var observer = null;
    var activeId = null;

    function latestText() {
      var nodes = document.querySelectorAll(SEL);
      if (!nodes.length) return '';
      return (nodes[nodes.length - 1].innerText || '').trim();
    }
    function tick() {
      if (!activeId || !state[activeId]) return;
      var t = latestText();
      var s = state[activeId];
      if (t && t !== s.baseline && t.length > (s.maxText || '').length) s.maxText = t;
    }
    return {
      begin: function (id) {
        activeId = id;
        state[id] = { baseline: latestText(), maxText: '' };
        if (!observer && document.documentElement) {
          observer = new MutationObserver(tick);
          observer.observe(document.documentElement, { subtree: true, childList: true, characterData: true });
        }
      },
      finish: function (id) {
        tick();
        var s = state[id] || { maxText: '' };
        var finalText = latestText();
        var max = s.maxText || '';
        // erased = a long reply was shown and has now been replaced by something much shorter / different
        var erased = max.length > 200 && (finalText.length < max.length * 0.5 || finalText.slice(0, 40) !== max.slice(0, 40));
        delete state[id];
        if (activeId === id) activeId = null;
        return { maxText: max, finalText: finalText, erased: erased };
      }
    };
  })();

  // expose for testing
  window.__storyBoardParser = Parser;
  window.__storyBoardSse = SseReader;
  log('capture installed');
})();
