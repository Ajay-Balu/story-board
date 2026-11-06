/*
 * Story-Board AI runner. Injected (at document start) into every chat page the app opens.
 *
 *   window.__sbRun(jobId, prompt)   types the prompt into the site's box, sends it, reports the reply
 *                                   (background actions: story bible, check, rewrite).
 *   window.__sbFill(text)           only fills the box (recap for a new chat).
 *   window.__sbWatch()              writing page on sites other than DeepSeek: reports every reply
 *                                   the site shows (new prompt, edited prompt, regenerate).
 *
 * How replies are read:
 *   DeepSeek - deepseek_capture.js (network stream + chat history).
 *   Grok     - Grok's conversation API once the reply is finished (background), page text (writing page).
 *   ChatGPT, Claude, Gemini - the reply on the page, finished when the Stop button is gone and the
 *              text has stopped changing.
 * Results go to window.StoryBoardBridge: onStart / onUpdate / onEnd / onError.
 */
(function () {
  if (window.__sbRunnerInstalled) return;
  window.__sbRunnerInstalled = true;

  var host = location.hostname;
  var SITES = {
    deepseek: {
      match: /deepseek\.com$/,
      box: ['textarea[placeholder*="DeepSeek"]', 'textarea#chat-input', 'textarea'],
      send: ['.ds-button--primary.ds-button--filled', 'button[type="submit"]']
    },
    grok: {
      match: /grok\.com$/,
      box: ['textarea[aria-label*="Ask Grok"]', 'form textarea', 'div[contenteditable="true"]', 'textarea'],
      send: ['button[type="submit"]', 'button[aria-label="Submit"]'],
      stop: ['button[aria-label*="Stop" i]'],
      reply: ['.response-content-markdown', '[class*="response-content"]', '.message-bubble .prose']
    },
    chatgpt: {
      match: /(chatgpt\.com|chat\.openai\.com)$/,
      box: ['#prompt-textarea', 'div[contenteditable="true"]#prompt-textarea', 'textarea[name="prompt-textarea"]', 'div[contenteditable="true"]', 'textarea'],
      send: ['button[data-testid="send-button"]', 'button[aria-label="Send message"]', 'button[aria-label="Send prompt"]', 'button[aria-label*="Send" i]'],
      stop: ['button[data-testid="stop-button"]', 'button[aria-label*="Stop" i]'],
      reply: ['li[data-message-role="assistant"] [data-assistant-markdown-root]', 'li[data-message-role="assistant"]',
              '[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]',
              // 2026-11 layout: no role attribute, the markdown root says it is an assistant message
              '[data-markdown-text-style="assistant-message"]', '[data-content-search-unit-key$=":assistant"]'],
      // the reply's container gets this attribute when the answer is complete
      complete: '[data-message-complete]'
    },
    claude: {
      match: /claude\.ai$/,
      box: ['div[contenteditable="true"].ProseMirror', 'div[contenteditable="true"][aria-label*="prompt" i]', 'div[contenteditable="true"]', 'textarea'],
      send: ['button[aria-label="Send message"]', 'button[aria-label*="Send" i]'],
      stop: ['button[aria-label="Stop response"]', 'button[aria-label*="Stop" i]'],
      busy: '[data-testid="assistant-message"][data-is-streaming="true"], [data-is-streaming="true"]',
      reply: ['[data-testid="assistant-message"]', '[data-cds="AssistantMessage"]', '.font-claude-response', '.font-claude-message'],
      // only the answer text, not the "Claude responded" heading, thinking summary or buttons
      text: ['[data-perf-reply-text]', '.standard-markdown', '[data-cds="Prose"]']
    },
    gemini: {
      match: /gemini\.google\.com$/,
      box: ['rich-textarea .ql-editor', 'div.ql-editor[contenteditable="true"]', 'div[contenteditable="true"]', 'textarea'],
      send: ['button.send-button', 'button[aria-label="Send message"]', 'button[aria-label*="Send" i]'],
      stop: ['button[aria-label="Stop response"]', 'button.stop', 'button[aria-label*="Stop" i]'],
      reply: ['model-response message-content .markdown', 'model-response message-content', 'message-content']
    }
  };
  var siteName = Object.keys(SITES).filter(function (k) { return SITES[k].match.test(host); })[0] || 'other';
  var site = SITES[siteName] || { box: ['textarea', 'div[contenteditable="true"]'], send: ['button[type="submit"]'], stop: [], reply: [] };

  // A site you taught the app: selectors come from the app (window.__sbSiteConfig, set before this script).
  // frame = host of the (i)frame that holds the message box; other frames only forward / watch for pictures.
  var CFG = null;
  var BUILTIN = { name: siteName, site: site };
  function applyConfig(c) {
    CFG = c || null;
    if (!CFG) { siteName = BUILTIN.name; site = BUILTIN.site; return; }
    siteName = 'custom';
    site = {
      // taught selector first; the generic ones cover a box / button that changes after the first message
      box: (CFG.box ? [CFG.box] : []).concat(['textarea', 'div[contenteditable="true"]', 'input[type="text"]']),
      send: (CFG.send ? [CFG.send] : []).concat(['button[type="submit"]', 'button[aria-label*="Send" i]']),
      stop: CFG.stop ? [CFG.stop] : ['button[aria-label*="Stop" i]'],
      reply: CFG.reply ? [CFG.reply] : [],
      image: CFG.image || null,
      prepare: CFG.prepare || null
    };
  }
  applyConfig(window.__sbSiteConfig || null);
  var isTop = window === window.top;
  function isWorkFrame() { return !CFG || !CFG.frame || CFG.frame === location.host; }
  /** Sends a message to every frame of the page (nested too). */
  function broadcast(msg) {
    (function walk(w) {
      for (var i = 0; i < w.frames.length; i++) {
        try { w.frames[i].postMessage(msg, '*'); } catch (e) {}
        try { walk(w.frames[i]); } catch (e) {}
      }
    })(window.top);
    try { if (!isTop) window.top.postMessage(msg, '*'); } catch (e) {}
  }

  function bridge() { return window.StoryBoardBridge; }
  function log(m) { try { console.log('[runner] ' + m); } catch (e) {} }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function fail(id, code, msg) { log('error ' + code + ' ' + msg); try { bridge().onError(id, code, msg || ''); } catch (e) {} }
  var seq = 0;
  function newId() { seq += 1; return siteName + '-' + Date.now() + '-' + seq; }

  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
  }
  function first(selectors, needVisible) {
    for (var i = 0; i < (selectors || []).length; i++) {
      var list = document.querySelectorAll(selectors[i]);
      for (var j = 0; j < list.length; j++) if (!needVisible || visible(list[j])) return list[j];
    }
    return null;
  }
  function all(selectors) {
    for (var i = 0; i < (selectors || []).length; i++) {
      var list = document.querySelectorAll(selectors[i]);
      if (list.length) return [].slice.call(list);
    }
    return [];
  }
  function stopVisible() { return !!first(site.stop, true) || !!(site.busy && document.querySelector(site.busy)); }

  /** Text of one reply: the answer parts only when the site marks them, else the whole node. */
  function replyText(node) {
    if (!node) return '';
    // ChatGPT "writing blocks" (a story set as a document): their clean markdown is in an attribute,
    // while their visible text carries the block's own header and buttons
    var blocks = node.querySelectorAll ? node.querySelectorAll('[data-oai-writing-block-surface][data-markdown-copy-text]') : [];
    if (blocks.length) {
      var whole = (node.innerText || '');
      [].forEach.call(blocks, function (b) {
        var vis = (b.innerText || '');
        var md = b.getAttribute('data-markdown-copy-text') || '';
        if (vis && md && whole.indexOf(vis) >= 0) whole = whole.replace(vis, md);
      });
      return whole.trim();
    }
    for (var i = 0; i < (site.text || []).length; i++) {
      var parts = [].slice.call(node.querySelectorAll(site.text[i]))
        .filter(function (el) { return !el.parentElement || !el.parentElement.closest(site.text[i]); });
      if (parts.length) return parts.map(function (el) { return (el.innerText || '').trim(); }).filter(Boolean).join('\n\n');
    }
    return (node.innerText || '').trim();
  }
  var lastPrompt = '';
  /**
   * Reply nodes, oldest first. Ordered by where they sit on screen (some chats build the list
   * bottom-up), and skipping the echo of the prompt we just sent (when the reply selector also
   * matches the user's own messages).
   */
  function replyNodes() {
    var list = all(site.reply);
    var p = (lastPrompt || '').replace(/\s+/g, ' ').trim();
    if (p) list = list.filter(function (n) {
      var t = (n.innerText || '').replace(/\s+/g, ' ').trim();
      if (!t) return true;
      if (t === p || (t.length > 40 && p.indexOf(t) === 0)) return false;
      // the user's own message, maybe with a label or a few extra words around it
      var head = p.slice(0, Math.min(80, p.length));
      return !(head.length >= 30 && t.indexOf(head) >= 0 && t.indexOf(head) < 40);
    });
    if (list.length > 1) {
      var pos = list.map(function (n, i) { var r = n.getBoundingClientRect(); return { n: n, i: i, y: r.top, h: r.height }; });
      if (pos.some(function (o) { return o.h > 0; }) && pos.some(function (o) { return o.y !== pos[0].y; })) {
        pos.sort(function (a, b) { return a.y - b.y || a.i - b.i; });
        list = pos.map(function (o) { return o.n; });
      }
    }
    return list;
  }

  function editable(el) {
    if (!el) return false;
    if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (el.tagName === 'INPUT') return /^(text|search|)$/i.test(el.type || '') && !el.readOnly && !el.disabled;
    return !!el.isContentEditable;
  }
  /** The message box: the first visible, typeable match. A taught element that isn't a text field
   *  (a wrapper or an output area) is replaced by the text field inside it, or skipped. */
  function findBox() {
    for (var i = 0; i < (site.box || []).length; i++) {
      var list = document.querySelectorAll(site.box[i]);
      for (var j = 0; j < list.length; j++) {
        var el = list[j];
        if (!visible(el)) continue;
        if (editable(el)) return el;
        var inner = [].slice.call(el.querySelectorAll('textarea, input[type="text"], input:not([type]), [contenteditable="true"], [contenteditable=""]'))
          .filter(function (c) { return visible(c) && editable(c); })[0];
        if (inner) return inner;
      }
    }
    return null;
  }

  function needsLogin() {
    var p = location.pathname + location.hostname;
    if (/sign_in|login|accounts\.x\.ai|accounts\.google\.com|auth\.openai\.com|sign-in|signin/i.test(p)) return true;
    if (siteName === 'grok' && !document.querySelector('[aria-label^="Account menu"]') &&
        /sign in|sign up/i.test((document.body && document.body.innerText || '').slice(0, 3000)) && !findBox()) return true;
    return false;
  }

  async function waitForBox(id) {
    for (var i = 0; i < 60; i++) {           // up to ~30 s
      if (needsLogin()) { fail(id, 'LOGIN_REQUIRED', location.href); return null; }
      var b = findBox();
      if (b) return b;
      await sleep(500);
    }
    fail(id, 'NO_INPUT', 'Could not find the message box on ' + location.href);
    return null;
  }

  function boxText(box) { return box.tagName === 'TEXTAREA' || box.tagName === 'INPUT' ? box.value : (box.innerText || ''); }

  function setValue(box, text) {
    box.focus();
    if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {
      var proto = box.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(box, text);
      box.dispatchEvent(new Event('input', { bubbles: true }));
      box.dispatchEvent(new Event('change', { bubbles: true }));
      return;
    }
    // Rich editors (ProseMirror, Quill, Lexical): select everything and insert as if typed / pasted.
    var sel = window.getSelection();
    var range = document.createRange();
    range.selectNodeContents(box);
    sel.removeAllRanges(); sel.addRange(range);
    var ok = false;
    try { ok = document.execCommand('insertText', false, text); } catch (e) {}
    if (!ok || boxText(box).trim().length < Math.min(20, text.trim().length)) {
      try {
        var dt = new DataTransfer();
        dt.setData('text/plain', text);
        box.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
      } catch (e) { log('paste failed ' + e); }
    }
    box.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function findSend(box) {
    var b = first(site.send, true);
    if (b && !/^(BUTTON|INPUT|A)$/.test(b.tagName) && b.getAttribute('role') !== 'button') {
      b = b.querySelector('button, [role="button"], input[type="submit"]') || b;
    }
    if (b) return b;
    var scope = box;
    for (var i = 0; i < 8 && scope.parentElement; i++) {
      scope = scope.parentElement;
      var c = scope.querySelector('button[type="submit"], button[aria-label*="Send" i]');
      if (c) return c;
    }
    return null;
  }

  function pressEnter(box) {
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      box.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    });
  }

  function disabled(btn) { return btn.disabled || btn.getAttribute('aria-disabled') === 'true'; }

  // ---------------------------------------------------------------- reading replies from the page
  /**
   * Follows the newest reply on the page until it is finished (Stop button gone and text stable),
   * reporting progress. Resolves with true when a reply was reported.
   */
  async function followReply(id, opts) {
    opts = opts || {};
    var countBefore = opts.countBefore != null ? opts.countBefore : -1;
    var started = false, last = '', max = '', stableSince = Date.now();
    var startDeadline = Date.now() + (opts.startTimeout || 120000);
    var deadline = Date.now() + 8 * 60 * 1000;
    while (Date.now() < deadline) {
      await sleep(700);
      var nodes = replyNodes();
      var node = nodes[nodes.length - 1];
      var fresh = node && (countBefore < 0 || nodes.length > countBefore || stopVisible());
      if (!started && fresh) {
        started = true;
        try { sessionStorage.setItem('__sbPending', String(Date.now())); } catch (e) {}
        try { bridge().onStart(id, opts.prompt || ''); } catch (e) {}
      }
      if (!started) {
        var perr = Date.now() - (startDeadline - (opts.startTimeout || 120000)) > 8000 ? pageError() : null;
        if (perr && opts.failOnTimeout) { fail(id, 'NO_REPLY', perr); return false; }
        if (Date.now() > startDeadline) { if (opts.failOnTimeout) fail(id, 'NO_REPLY', 'No reply appeared on ' + siteName); return false; }
        continue;
      }
      var text = replyText(node);
      if (text !== last) {
        last = text; stableSince = Date.now();
        if (text.length > max.length) max = text;
        try { bridge().onUpdate(id, text); } catch (e) {}
      }
      var markedDone = site.complete && node && node.closest && node.closest(site.complete);
      if (!stopVisible() && last && (Date.now() - stableSince > 2500 || (markedDone && Date.now() - stableSince > 1000))) {
        var erased = max.length > 200 && last.length < max.length * 0.5;
        var out = erased ? max : last;
        try { bridge().onEnd(id, out, JSON.stringify({ erased: erased, service: siteName, source: 'page', finalLength: last.length, maxLength: max.length })); } catch (e) {}
        try { sessionStorage.removeItem('__sbPending'); } catch (e) {}
        return true;
      }
    }
    if (last) { try { bridge().onEnd(id, max || last, JSON.stringify({ erased: false, service: siteName, source: 'page', timeout: true })); } catch (e) {} return true; }
    fail(id, 'TIMEOUT', siteName + ' did not finish in time');
    return false;
  }

  // ---------------------------------------------------------------- Grok reply via API (background)
  async function grokWait(id) {
    var cid = null, last = '', started = false;
    var deadline = Date.now() + 6 * 60 * 1000;
    while (Date.now() < deadline) {
      await sleep(1500);
      if (!cid) {
        var m = location.pathname.match(/\/c\/([\w-]+)/);
        if (!m) continue;
        cid = m[1];
        log('grok conversation ' + cid);
      }
      try {
        var r1 = await fetch('/rest/app-chat/conversations/' + cid + '/response-node', { credentials: 'include' });
        if (r1.status === 401 || r1.status === 403) { fail(id, 'LOGIN_REQUIRED', 'grok ' + r1.status); return; }
        var j1 = await r1.json();
        var a = (j1.responseNodes || []).filter(function (n) { return n.sender === 'assistant'; }).pop();
        if (!a) continue;
        var r2 = await fetch('/rest/app-chat/conversations/' + cid + '/load-responses', {
          method: 'POST', credentials: 'include', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ responseIds: [a.responseId] })
        });
        var j2 = await r2.json();
        var resp = j2.responses && j2.responses[0];
        if (!resp) continue;
        var msg = resp.message || '';
        if (!started) { started = true; try { bridge().onStart(id, ''); } catch (e) {} }
        if (msg !== last) { last = msg; try { bridge().onUpdate(id, msg); } catch (e) {} }
        var inflight = (j1.inflightResponses || []).length > 0;
        var errors = resp.streamErrors && resp.streamErrors.length
          ? resp.streamErrors.map(function (e) { return e.message || JSON.stringify(e); }).join('; ') : null;
        if (!inflight && !resp.partial && msg) {
          try { bridge().onEnd(id, msg, JSON.stringify({ erased: false, service: 'grok', errors: errors })); } catch (e) {}
          return;
        }
        if (!inflight && errors && !msg) { fail(id, 'AI_ERROR', 'Grok: ' + errors); return; }
      } catch (e) { log('poll error ' + e); }
    }
    fail(id, 'TIMEOUT', 'Grok did not finish in time');
  }

  // ---------------------------------------------------------------- pictures
  var MIN_SIDE = 200;
  function imgSrc(img) { return img.currentSrc || img.src || ''; }
  function pictureNodes() {
    var list;
    if (site.image) {
      list = [];
      [].slice.call(document.querySelectorAll(site.image)).forEach(function (el) {
        if (el.tagName === 'IMG') list.push(el);
        else [].slice.call(el.querySelectorAll('img')).forEach(function (i) { list.push(i); });
      });
    } else list = [].slice.call(document.images);
    return list;
  }
  function bigEnough(img) {
    return img.complete && img.naturalWidth >= MIN_SIDE && img.naturalHeight >= MIN_SIDE && imgSrc(img);
  }
  function blobToDataUrl(blob) {
    return new Promise(function (res, rej) { var r = new FileReader(); r.onload = function () { res(r.result); }; r.onerror = rej; r.readAsDataURL(blob); });
  }
  /** data: URL when the page can read the picture itself (blob:, same site), else the web address for the app to download. */
  function canvasData(img) {
    try {
      var c = document.createElement('canvas');
      c.width = img.naturalWidth; c.height = img.naturalHeight;
      c.getContext('2d').drawImage(img, 0, 0);
      var d = c.toDataURL('image/png');
      return d && d.length > 1000 ? d : null;
    } catch (e) { log('canvas copy failed: ' + e); return null; }
  }
  async function portable(src, img) {
    if (/^data:/.test(src)) return src;
    try {
      var r = await fetch(src, { credentials: 'include' });
      if (r.ok) {
        var b = await r.blob();
        if (b.size > 1000 && /^image\//.test(b.type)) return await blobToDataUrl(b);
      }
    } catch (e) { log('picture fetch in page failed: ' + e); }
    // blob: pictures that can't be fetched any more (Gemini) are copied from the drawn picture
    var drawn = img && /^blob:/.test(src) ? canvasData(img) : null;
    if (drawn) return drawn;
    return /^blob:/.test(src) ? null : src;
  }

  /** Text of an error message the site shows (alert boxes, red error text), if any. */
  function pageError() {
    var els = document.querySelectorAll('[role="alert"], [class*="text-token-text-error"], [class*="error-message"], .text-red-500');
    for (var i = 0; i < els.length; i++) {
      var t = (els[i].innerText || '').trim().split('\n')[0].trim();
      if (t && t.length < 300 && visible(els[i])) return t;
    }
    // a length limit printed anywhere near the end of the page ("user_input too long (max 4000 chars)")
    var tail = (document.body && document.body.innerText || '').slice(-1500);
    var m = tail.split('\n').map(function (l) { return l.trim(); }).filter(function (l) {
      return l.length < 140 && (/(input|message|prompt|text)\S*\s+(is\s+)?too long/i.test(l) || /max(imum)?\s*:?\s*\d[\d,]*\s*(chars|characters)/i.test(l) || /character limit/i.test(l));
    }).slice(-1);
    if (m.length) return 'The site refused the message: ' + m[0].replace(/^[^\w]+/, '').trim().slice(0, 200) + '. Turn off "Include the story" or use another AI.';
    return null;
  }

  var imageWatches = {};
  /** Watches this frame for new pictures; reports them once they stop changing. */
  var AD_HOST = /(^|[.-])(ads?|adservice|ad-sandbox|doubleclick|googlesyndication|exoclick|taboola|outbrain)([.-]|$)/i;
  function watchPictures(id, opts) {
    if (imageWatches[id]) return;
    if (!opts.work && AD_HOST.test(location.hostname)) return;   // advert frames
    var before = {};
    pictureNodes().forEach(function (i) { before[imgSrc(i)] = 1; });
    [].slice.call(document.images).forEach(function (i) { before[imgSrc(i)] = 1; });
    imageWatches[id] = true;
    (async function () {
      var deadline = Date.now() + (opts.timeout || 6 * 60 * 1000);
      var seen = '', lastChange = Date.now(), started = Date.now(), replyStable = 0, lastText = '';
      while (Date.now() < deadline && imageWatches[id]) {
        await sleep(1000);
        var fresh = pictureNodes().filter(function (i) { return !before[imgSrc(i)] && bigEnough(i); });
        var key = fresh.map(imgSrc).join('|');
        if (key !== seen) { seen = key; lastChange = Date.now(); if (fresh.length) try { bridge().onUpdate(id, 'Pictures: ' + fresh.length); } catch (e) {} }
        var busy = opts.work && stopVisible();
        if (fresh.length && !busy && Date.now() - lastChange > 6000) {
          var urls = [];
          for (var k = 0; k < fresh.length; k++) {
            var u = await portable(imgSrc(fresh[k]), fresh[k]);
            if (u && urls.indexOf(u) < 0) urls.push(u);
          }
          delete imageWatches[id];
          log('pictures ready: ' + urls.length);
          try { bridge().onImages(id, JSON.stringify(urls)); } catch (e) {}
          return;
        }
        // The site showed an error and no reply started (e.g. "Your account changed. Send your message again").
        if (opts.work && !CFG && !fresh.length && !busy && Date.now() - started > 40000 && replyNodes().length <= (opts.countBefore || 0)) {
          var err = pageError();
          if (err || Date.now() - started > 120000) {
            delete imageWatches[id];
            fail(id, 'NO_REPLY', err || 'No reply appeared on ' + siteName);
            return;
          }
        }
        // Built-in chats: a finished text reply without any picture (refused, asked a question...).
        if (opts.work && !CFG && !fresh.length && !busy && Date.now() - started > 15000) {
          var nodes = replyNodes(), t = replyText(nodes[nodes.length - 1]);
          if (nodes.length > (opts.countBefore || 0) && t) {
            if (t === lastText) replyStable += 1; else { lastText = t; replyStable = 0; }
            if (replyStable >= 12) {
              delete imageWatches[id];
              fail(id, 'NO_IMAGE', t.slice(0, 400));
              return;
            }
          }
        }
      }
      delete imageWatches[id];
    })();
  }

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object' || !d.__sb) return;
    if (d.__sb === 'run' && isWorkFrame() && !isTop) window.__sbRun(d.id, d.prompt);
    else if (d.__sb === 'image' && isWorkFrame() && !isTop) window.__sbImage(d.id, d.prompt);
    else if (d.__sb === 'img-watch' && !isWorkFrame()) watchPictures(d.id, { work: false });
    else if (d.__sb === 'img-stop') { delete imageWatches[d.id]; if (activeImage === d.id) activeImage = null; }
    // a frame that appeared after the request (Perchance draws each picture in a new frame) joins the watch
    else if (d.__sb === 'need-watch' && isTop && activeImage) broadcast({ __sb: 'img-watch', id: activeImage });
    else if (d.__sb === 'watch' && isWorkFrame() && !isTop) window.__sbWatch();
    else if (d.__sb === 'fill' && isWorkFrame() && !isTop) window.__sbFill(d.text);
    else if (d.__sb === 'cfg') applyConfig(d.cfg);
    else if (d.__sb === 'clearbox' && isWorkFrame() && !isTop) window.__sbClearBox();
    else if (d.__sb === 'teach-at') { var el = document.elementFromPoint(d.x, d.y); if (el) teachAt(d.step, el, d.x, d.y); }
  });

  /** Types [prompt] and sends it; returns the number of replies before sending, or -1 when no box. */
  async function typeAndSend(id, prompt, checkSent) {
    // a taught "before typing" button (e.g. a site's "New Image" mode) is pressed first
    if (site.prepare) {
      var prep = first([site.prepare], true);
      if (prep) { prep.click(); log('pressed the before-typing button'); await sleep(1200); }
      else log('before-typing button not found; typing anyway');
    }
    var box = await waitForBox(id);
    if (!box) return -1;
    await sleep(400);
    lastPrompt = prompt;
    var countBefore = replyNodes().length;
    setValue(box, prompt);
    await sleep(900);
    // some sites (Perchance) restore their last prompt after loading and overwrite ours: check and set again
    for (var k = 0; k < 4; k++) {
      var nb = findBox() || box;
      if (boxText(nb).replace(/\s+/g, ' ').trim() === prompt.replace(/\s+/g, ' ').trim()) break;
      log('the box lost the prompt, setting it again');
      box = nb; setValue(box, prompt); await sleep(700);
    }
    var send = findSend(box);
    for (var i = 0; i < 10 && send && disabled(send); i++) { await sleep(300); send = findSend(box); }
    if (send && !disabled(send)) send.click();
    else pressEnter(box);
    // make sure it went: a sent message leaves the box (empty, or a new one). Click again, then try Enter.
    for (var tries = 0; checkSent && tries < 3; tries++) {
      await sleep(1500);
      var bx = findBox();
      if (!bx || norm(boxText(bx)).length < Math.min(20, norm(prompt).length) || stopVisible() || replyNodes().length > countBefore) break;
      var again = findSend(bx);
      log('the message is still in the box, sending again (' + (tries + 1) + ')');
      if (tries < 2 && again && !disabled(again)) again.click(); else pressEnter(bx);
    }
    log('sent ' + prompt.length + ' chars via ' + (send ? 'button' : 'enter') + ' on ' + siteName);
    // drop focus so the phone keyboard doesn't pop up over a visible page
    setTimeout(function () { try { var a = document.activeElement; if (a && a !== document.body && a.blur) a.blur(); } catch (e) {} }, 400);
    return countBefore;
  }

  /** Asks for a picture: sends [prompt], then reports new pictures (onImages) from any frame. */
  /** The app has the pictures: every frame stops watching. */
  window.__sbImageDone = function (id) { delete imageWatches[id]; activeImage = null; broadcast({ __sb: 'img-stop', id: id }); return 'ok'; };
  var activeImage = null;
  if (!isTop && CFG) { try { window.top.postMessage({ __sb: 'need-watch' }, '*'); } catch (e) {} }
  window.__sbImage = async function (id, prompt) {
    if (isTop) { activeImage = id; setTimeout(function () { if (activeImage === id) activeImage = null; }, 8 * 60 * 1000); }
    if (!isWorkFrame()) { broadcast({ __sb: 'image', id: id, prompt: prompt }); broadcast({ __sb: 'img-watch', id: id }); return 'forwarded'; }
    if (CFG) broadcast({ __sb: 'img-watch', id: id });
    watchPictures(id, { work: true, countBefore: replyNodes().length });
    var before = await typeAndSend(id, prompt);
    if (before < 0) { delete imageWatches[id]; return 'no-box'; }
    try { bridge().onStart(id, prompt); } catch (e) {}
    return 'sent';
  };

  // ---------------------------------------------------------------- teach a site
  var teaching = null, teachBox = null;
  function esc(v) { return (window.CSS && CSS.escape) ? CSS.escape(v) : v.replace(/[^\w-]/g, '\\$&'); }
  function stableClass(c) {
    if (!c || c.length >= 32 || /\d{3,}|^css-|^sc-|^_|[:\/\[]/.test(c)) return false;
    // build-hash class names (e.g. "TmiyHFYeTH6BRfiFO5eV") change with every site update
    if (c.length >= 12 && /[A-Z]/.test(c) && /[a-z]/.test(c) && !/-/.test(c)) return false;
    return true;
  }
  /** tag + stable classes (no position), e.g. "div.markdown.prose". */
  function simpleSel(el) {
    var cls = [].slice.call(el.classList).filter(stableClass).slice(0, 3);
    return el.tagName.toLowerCase() + (cls.length ? '.' + cls.map(esc).join('.') : '');
  }
  var ROLE_WORDS = /assistant|bot|model|response|answer|reply|ai-message/i;
  /** Selector for "the AI's replies": anchored on an ancestor marked as the assistant's message when there is one. */
  function replySelector(textEl) {
    // the text block itself should be named by stable classes: look inside for one that holds (almost) all the text
    var want = (textEl.innerText || '').length;
    if (simpleSel(textEl).indexOf('.') < 0) {
      var all = [textEl].concat([].slice.call(textEl.querySelectorAll('*')));
      for (var k = 0; k < all.length; k++) {
        if (simpleSel(all[k]).indexOf('.') > 0 && (all[k].innerText || '').length >= want * 0.9) { textEl = all[k]; break; }
      }
    }
    for (var a = textEl, depth = 0; a && a !== document.body && depth < 12; a = a.parentElement, depth++) {
      for (var i = 0; i < a.attributes.length; i++) {
        var at = a.attributes[i];
        if (at.name === 'class' || at.name === 'style') continue;
        var m = (at.value || '').match(ROLE_WORDS) || at.name.match(ROLE_WORDS);
        if (!m) continue;
        var anchor = at.value && at.value.match(ROLE_WORDS)
          ? a.tagName.toLowerCase() + '[' + at.name + '*="' + m[0] + '"]'
          : a.tagName.toLowerCase() + '[' + at.name + ']';
        var full = a === textEl ? anchor : anchor + ' ' + simpleSel(textEl);
        var list = document.querySelectorAll(full);
        if (list.length && [].slice.call(list).indexOf(textEl) >= 0) {
          // a nested match would duplicate text: keep only the outermost
          return full;
        }
        if (count(anchor) >= 1) return anchor;
      }
    }
    return selectorFor(textEl, true);
  }
  function count(sel) { try { return document.querySelectorAll(sel).length; } catch (e) { return 0; } }
  /** A CSS selector for [el]: unique for boxes and buttons, shared by all replies / pictures when [many]. */
  function selectorFor(el, many) {
    var tag = el.tagName.toLowerCase();
    if (!many && el.id && !/\d{3,}/.test(el.id) && count('#' + esc(el.id)) === 1) return '#' + esc(el.id);
    // the only textarea / input on the page: the tag alone survives text changes (Perchance rotates its placeholder)
    if (!many && /^(textarea|input)$/.test(tag) &&
        [].slice.call(document.querySelectorAll(tag)).filter(function (x) { return visible(x); }).length === 1) return tag;
    var attrs = ['data-testid', 'data-test-id', 'name', 'aria-label', 'type', 'role', 'contenteditable', 'placeholder'];
    for (var i = 0; i < attrs.length; i++) {
      var v = el.getAttribute(attrs[i]);
      if (v && v.length < 60) {
        var s = tag + '[' + attrs[i] + '="' + v.replace(/"/g, '\\"') + '"]';
        if (many ? count(s) >= 1 : count(s) === 1) return s;
      }
    }
    var cls = [].slice.call(el.classList).filter(stableClass).slice(0, 3);
    if (cls.length) {
      var s2 = tag + '.' + cls.map(esc).join('.');
      if (many || count(s2) === 1) return s2;
    }
    if (many) {
      var p = el.parentElement;
      return p && p !== document.body ? selectorFor(p, true) + ' > ' + tag : tag;
    }
    var parent = el.parentElement;
    if (!parent || parent === document.documentElement) return tag;
    var same = [].slice.call(parent.children).filter(function (c) { return c.tagName === el.tagName; });
    var part = same.length > 1 ? tag + ':nth-of-type(' + (same.indexOf(el) + 1) + ')' : tag;
    return selectorFor(parent, false) + ' > ' + part;
  }
  function pickFor(step, el) {
    if (step === 'box') return el.closest('textarea, input, [contenteditable="true"], [contenteditable=""]') || el;
    if (step === 'send' || step === 'stop' || step === 'prepare') {
      // a disabled button often has pointer-events:none, so the tap lands on its wrapper
      return el.closest('button, [role="button"], input[type="submit"], a') ||
        el.querySelector('button, [role="button"], input[type="submit"]') || el;
    }
    if (step === 'image') return el.tagName === 'IMG' ? el : (el.querySelector('img') || el.closest('figure, a') || el);
    return el;   // reply: the tapped block
  }
  function highlight(el) {
    if (!teachBox) {
      teachBox = document.createElement('div');
      teachBox.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:3px solid #ff3d7f;background:rgba(255,61,127,.15);border-radius:6px;transition:all .1s';
      document.documentElement.appendChild(teachBox);
    }
    var r = el.getBoundingClientRect();
    teachBox.style.left = r.left + 'px'; teachBox.style.top = r.top + 'px';
    teachBox.style.width = r.width + 'px'; teachBox.style.height = r.height + 'px';
    teachBox.style.display = 'block';
  }
  // A transparent cover catches the tap (disabled buttons get no events of their own); the element
  // under the finger is then found with elementFromPoint.
  var teachCover = null;
  function underFinger(e) {
    var t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
    teachCover.style.display = 'none';
    if (teachBox) teachBox.style.display = 'none';
    var el = document.elementFromPoint(t.clientX, t.clientY);
    teachCover.style.display = 'block';
    return el;
  }
  // The cover also scrolls the page (or the scrollable box under the finger) on a drag, so you can
  // reach a button lower down; only a tap (little movement) teaches.
  var drag = null;
  function scrollerOf(el) {
    for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      var oy = getComputedStyle(e).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && e.scrollHeight > e.clientHeight + 2) return e;
    }
    return document.scrollingElement || document.documentElement;
  }
  function onTeachDown(e) {
    if (!teaching) return;
    e.preventDefault(); e.stopPropagation();
    var el = underFinger(e);
    drag = { x: e.clientX, y: e.clientY, lastY: e.clientY, moved: false, scroller: el ? scrollerOf(el) : null };
    if (el) highlight(pickFor(teaching, el));
  }
  function onTeachMove(e) {
    if (!teaching || !drag) return;
    e.preventDefault();
    if (Math.abs(e.clientY - drag.y) > 12 || Math.abs(e.clientX - drag.x) > 12) drag.moved = true;
    if (drag.moved && drag.scroller) {
      drag.scroller.scrollTop -= (e.clientY - drag.lastY);
      if (teachBox) teachBox.style.display = 'none';
    }
    drag.lastY = e.clientY;
  }
  function onTeachUp(e) {
    if (!teaching || !drag) return;
    e.preventDefault(); e.stopPropagation();
    var wasTap = !drag.moved;
    drag = null;
    if (!wasTap) return;
    var target = underFinger(e);
    if (!target) return;
    var step = teaching;
    teaching = null;
    setTimeout(function () { stopTeach(); }, 400);   // keep covering until the tap ends
    teachAt(step, target, e.clientX, e.clientY);
  }
  /** Reports the element at x,y; inside an iframe the frame itself is asked (it may be another site). */
  function teachAt(step, target, x, y) {
    // some pages focus their prompt box on any touch; keep the phone keyboard down while teaching
    [50, 600].forEach(function (ms) { setTimeout(function () { try { var a = document.activeElement; if (a && a !== document.body && a.blur) a.blur(); } catch (e) {} }, ms); });
    if (target.tagName === 'IFRAME' && target.contentWindow) {
      var r = target.getBoundingClientRect();
      target.contentWindow.postMessage({ __sb: 'teach-at', step: step, x: x - r.left, y: y - r.top }, '*');
      return;
    }
    var el = pickFor(step, target);
    var many = step === 'reply' || step === 'image';
    var sel = selectorFor(el, many);
    if (step === 'reply') {
      var cur = el;
      // tapped a decoration without text: go up to the block that has the words
      while ((cur.innerText || '').trim().length < 2 && cur.parentElement && cur.parentElement !== document.body) cur = cur.parentElement;
      // words are often wrapped in inline spans (streaming effects): start from the paragraph / block
      while (cur.parentElement && cur.parentElement !== document.body && /^inline/.test(getComputedStyle(cur).display) &&
             getComputedStyle(cur).display !== 'inline-block') cur = cur.parentElement;
      // prefer the block that holds the whole reply: walk up while the parent adds little else
      while (cur.parentElement && cur.parentElement !== document.body &&
             (cur.parentElement.innerText || '').length < (cur.innerText || '').length * 1.3) cur = cur.parentElement;
      sel = replySelector(cur);
      el = cur;
    }
    var info = {
      step: step, selector: sel, frame: location.host, top: isTop, tag: el.tagName.toLowerCase(),
      matches: count(sel), text: ((el.innerText || el.value || el.getAttribute('aria-label') || '') + '').trim().slice(0, 160),
      src: el.tagName === 'IMG' ? imgSrc(el).slice(0, 160) : ''
    };
    log('taught ' + JSON.stringify(info));
    try { bridge().onTeach(step, JSON.stringify(info)); } catch (err) {}
  }
  function startTeach(step) {
    stopTeach();
    teaching = step;
    if (!document.documentElement) return;
    teachCover = document.createElement('div');
    teachCover.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483646;background:transparent;touch-action:none';
    teachCover.addEventListener('pointerdown', onTeachDown, true);
    teachCover.addEventListener('pointermove', onTeachMove, true);
    teachCover.addEventListener('pointerup', onTeachUp, true);
    teachCover.addEventListener('pointercancel', function () { drag = null; }, true);
    teachCover.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); }, true);
    document.documentElement.appendChild(teachCover);
  }
  function stopTeach() {
    teaching = null;
    if (teachCover) { teachCover.remove(); teachCover = null; }
    if (teachBox) teachBox.style.display = 'none';
  }
  /** Teach mode: new selectors for this page and all its frames. */
  window.__sbSetConfig = function (c) { applyConfig(c); broadcast({ __sb: 'cfg', cfg: c }); return 'ok'; };

  /** Teach mode: the next tap on the page (in any frame) is reported as the [step] element. */
  window.__sbTeach = function (step) {
    if (!step) { stopTeach(); return 'stopped'; }
    startTeach(step);
    return 'teaching';
  };

  // ---------------------------------------------------------------- public API
  window.__sbRun = async function (id, prompt) {
    if (!isWorkFrame()) { broadcast({ __sb: 'run', id: id, prompt: prompt }); return 'forwarded'; }
    if (CFG && !CFG.reply) { fail(id, 'NOT_TAUGHT', 'Teach this site where the reply appears first'); return 'no-reply'; }
    // kept until sent: a site that reloads itself right after loading (ChatGPT) would otherwise lose the prompt
    try { sessionStorage.setItem('__sbJob', JSON.stringify({ id: id, prompt: prompt, t: Date.now() })); } catch (e) {}
    var countBefore = await typeAndSend(id, prompt, true);
    try { sessionStorage.removeItem('__sbJob'); } catch (e) {}
    if (countBefore < 0) return 'no-box';
    if (siteName === 'grok') grokWait(id);
    else if (siteName !== 'deepseek') followReply(id, { countBefore: countBefore, prompt: prompt, failOnTimeout: true });
    // DeepSeek: deepseek_capture.js reports the reply through the same bridge.
    return 'sent';
  };

  /**
   * Recap for a new chat. Kept in sessionStorage until the real message box exists and the text
   * has stuck, so it survives the site still loading or reloading the page (ChatGPT does both).
   */
  var filling = false;
  async function fillPending() {
    if (filling) return 'busy';
    var t = null;
    try { t = sessionStorage.getItem('__sbRecap'); } catch (e) {}
    if (!t) return 'none';
    filling = true;
    try {
      for (var i = 0; i < 60; i++) {
        var box = findBox();
        if (box && !needsLogin()) {
          // replace whatever is there: the site may have restored an old unsent draft (even another book's)
          if (norm(boxText(box)) !== norm(t)) setValue(box, t);
          await sleep(1500);
          var b2 = findBox();
          if (b2 && norm(boxText(b2)).length >= Math.min(40, Math.floor(norm(t).length / 2)) && norm(boxText(b2)).indexOf(norm(t).slice(0, 30)) === 0) {
            try { sessionStorage.removeItem('__sbRecap'); } catch (e) {}
            log('recap filled (' + t.length + ' chars)');
            // some sites (ChatGPT) rebuild their message box once more after loading: put the text back if it vanishes
            (async function () {
              for (var k = 0; k < 20; k++) {
                await sleep(1000);
                var b3 = findBox();
                if (b3 && boxText(b3).trim().length < 5) { setValue(b3, t); log('recap filled again'); }
              }
            })();
            return 'filled';
          }
        }
        await sleep(750);
      }
      return 'gave-up';
    } finally { filling = false; }
  }
  function norm(x) { return String(x || '').replace(/\s+/g, ' ').trim(); }
  /** A new chat for a book with nothing to recap: empty the box (the site may restore an old unsent draft). */
  window.__sbClearBox = function () {
    if (!isWorkFrame()) { broadcast({ __sb: 'clearbox' }); return 'forwarded'; }
    try { sessionStorage.removeItem('__sbRecap'); } catch (e) {}
    (async function () {
      for (var i = 0; i < 40; i++) {
        var box = findBox();
        if (box && !needsLogin()) {
          await sleep(1200);   // let the site restore its draft first
          box = findBox() || box;
          if (norm(boxText(box))) { setValue(box, ''); log('cleared an old draft from the box'); }
          return;
        }
        await sleep(500);
      }
    })();
    return 'clearing';
  };
  window.__sbFill = function (text) {
    if (!isWorkFrame()) { broadcast({ __sb: 'fill', text: text }); return 'forwarded'; }
    try { sessionStorage.setItem('__sbRecap', text); } catch (e) {}
    return fillPending();
  };
  // A recap requested before a reload is finished on the new page.
  setTimeout(fillPending, 1500);
  // A prompt handed over just before the site reloaded itself: send it on the new page.
  setTimeout(function () {
    if (!isWorkFrame()) return;
    var job = null;
    try { job = JSON.parse(sessionStorage.getItem('__sbJob') || 'null'); } catch (e) {}
    if (!job || !job.id || Date.now() - job.t > 120000) { try { sessionStorage.removeItem('__sbJob'); } catch (e) {} return; }
    log('resuming a prompt that was not sent before the page reloaded');
    window.__sbRun(job.id, job.prompt);
  }, 2500);

  /** Writing page (not DeepSeek): report every reply that appears or is regenerated. */
  window.__sbWatch = function () {
    if (!isWorkFrame()) { broadcast({ __sb: 'watch' }); return 'forwarded'; }
    if (window.__sbWatching || siteName === 'deepseek' || (CFG && !CFG.reply)) return 'skip';
    window.__sbWatching = true;
    // Mark "a reply is on its way" when you send, so it survives the site reloading the page
    // (ChatGPT, for example, moves a new chat to its own address with a full page load).
    function markPending() { try { sessionStorage.setItem('__sbPending', String(Date.now())); } catch (e) {} }
    document.addEventListener('click', function (e) {
      var t = e.target && e.target.closest && e.target.closest((site.send || []).join(','));
      if (t) markPending();
    }, true);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && e.target && e.target.closest && e.target.closest((site.box || []).join(','))) markPending();
    }, true);
    (async function loop() {
      await sleep(3000);                     // let the existing conversation render first
      var pending = 0;
      try { pending = +sessionStorage.getItem('__sbPending') || 0; } catch (e) {}
      if (pending && Date.now() - pending < 5 * 60 * 1000 && replyNodes().length) {
        log('reply continued after page load on ' + siteName);
        await followReply(newId(), { countBefore: -1 });
      }
      var known = replyNodes().length;
      var lastFinal = replyText(replyNodes().slice(-1)[0]);
      while (true) {
        await sleep(800);
        var nodes = replyNodes();
        var lastText = replyText(nodes[nodes.length - 1]);
        var busy = stopVisible();
        if (nodes.length > known || busy || (nodes.length && lastText !== lastFinal && nodes.length === known && lastText.length < lastFinal.length * 0.3)) {
          var id = newId();
          log('reply started on ' + siteName);
          await followReply(id, { countBefore: -1 });
          nodes = replyNodes();
          known = nodes.length;
          lastFinal = replyText(nodes[nodes.length - 1]);
        }
      }
    })();
    return 'watching';
  };

  log('runner installed on ' + host + ' (' + siteName + ')');
})();
