(function () {
  'use strict';
  if (window.__ghcnImmersive && window.__ghcnImmersive.version === 2) return;

  var originals = new WeakMap(), entries = new Map(), sequence = 0;
  var token = Date.now().toString(36) + Math.random().toString(36).slice(2);
  var epoch = 0, enabled = false, target = '', dirty = true;
  var address = window.location.href, revision = -1;
  var heightQueued = false;
  var bilingual = false, nativePage = null, nativeSequence = 0;
  var pageCache = new Map(), cacheSize = 0;
  var skip = 'pre,code,kbd,samp,script,style,svg,input,textarea,select,button,[contenteditable="true"],[translate="no"],.notranslate,[data-ghcn-original],[data-ghcn-loading]';

  function local() {
    var l = window.location;
    return (l.protocol === 'https:' && l.hostname === 'appassets.androidplatform.net' &&
      l.pathname.indexOf('/android_asset/webview/') === 0) ||
      ((l.href === 'about:blank' || l.protocol === 'file:' || l.protocol === 'content:') &&
       !!document.querySelector('main#content.markdown-body') &&
       !!document.querySelector('script[src*="/android_asset/webview/"]'));
  }
  function trusted() {
    return local() || (window.location.protocol === 'https:' &&
      (window.location.hostname === 'github.com' || window.location.hostname === 'www.github.com'));
  }
  function documentKey() { return token + '|' + window.location.href + (nativePage ? '|native:' + nativePage.serial : ''); }
  function updateHeight() {
    if (!local() || heightQueued || !window.github || typeof window.github._updateHeight !== 'function') return;
    heightQueued = true;
    var update = function () {
      heightQueued = false;
      // Reuse GitHub's existing height -> native bridge, including its unchanged-height cache.
      try { if (local() && window.github) window.github._updateHeight(); } catch (ignored) {}
    };
    if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(update);
    else update();
  }
  function roots() {
    var selector = local() ? '.markdown-body,#readme' :
      '.markdown-body,#readme,.comment-body,[itemprop="about"],[itemprop="description"],[data-testid="repository-description"]';
    var list = Array.from(document.querySelectorAll(selector));
    return list.filter(function (node, i) {
      return !list.some(function (other, j) { return j !== i && other.contains(node); });
    });
  }
  function eligible(node) {
    var parent = node.parentElement, text = node.data.trim();
    if (!parent || parent.closest(skip) || text.length < 3 || !/[A-Za-z]/.test(text)) return false;
    if (/[\u3400-\u9fff\u3040-\u30ff]/.test(text)) return false;
    if (/^(https?:\/\/\S+|[a-f0-9]{7,64}|[\w./-]+\.(js|ts|py|md|json|yaml|yml|sh|java|zip|apk))$/i.test(text)) return false;
    // ponytail: individual oversized nodes exceed the provider's safe Binder budget.
    return text.length <= 24000;
  }
  function scan() {
    roots().forEach(function (root) {
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), node;
      while ((node = walker.nextNode())) {
        var e = originals.get(node);
        if (e && enabled && e.translated !== null && node.data === e.original) node.data = e.translated;
        if (!eligible(node)) continue;
        if (!e) {
          e = { id: ++sequence, node: node, original: node.data, translated: null,
            pending: null, retryAt: 0, error: null };
          originals.set(node, e); entries.set(e.id, e);
        } else if (node.data !== e.original && node.data !== e.translated) {
          // React/markdown rendering replaced the content of this existing text node.
          entries.delete(e.id); e.id = ++sequence;
          e.original = node.data; e.translated = null; e.pending = null;
          e.retryAt = 0; e.error = null;
        }
        entries.set(e.id, e);
      }
    });
    entries.forEach(function (e, id) {
      if (!document.contains(e.node)) entries.delete(id);
    });
    dirty = false;
  }
  function restore() {
    entries.forEach(function (e) {
      if (e.translated !== null && e.node.data === e.translated) e.node.data = e.original;
      e.translated = null; e.pending = null; e.retryAt = 0; e.error = null;
    });
    dirty = true;
    updateHeight();
  }
  var observedRoot = null;
  var observer = new MutationObserver(function () { dirty = true; });
  function observeRoot() {
    var root = document.documentElement;
    if (root === observedRoot) return;
    observer.disconnect();
    observedRoot = root;
    if (root) observer.observe(root, { subtree: true, childList: true, characterData: true });
    // Native WebViews can inject before the template root is ready, or replace it later.
    dirty = true;
  }

  function cacheKey(html) { return target + '\u0000' + html; }
  function remember(key, value) {
    if (pageCache.has(key)) return;
    value.size = key.length + value.translated.length + value.bilingual.length;
    if (value.size > 600000) return;
    pageCache.set(key, value); cacheSize += value.size;
    while (pageCache.size > 6 || cacheSize > 600000) {
      var oldest = pageCache.keys().next().value;
      cacheSize -= pageCache.get(oldest).size; pageCache.delete(oldest);
    }
  }
  function nativeRender(page, html) {
    if (page !== nativePage) return;
    var args = page.args.slice(); args[1] = html;
    page.original.apply(page.receiver, args);
    page.displayed = html;
    if (page.afterLoad) page.afterLoad();
  }
  function afterLoad(fn) {
    if (typeof fn !== 'function') return;
    var page = nativePage;
    if (!page) { fn(); return; }
    page.afterLoad = fn;
    if (!enabled || page.ready || page.suspended) fn();
  }
  function nativeReady(page) {
    if (page !== nativePage || page.ready || page.suspended || !enabled) return;
    if (page.entries.some(function (entry) { return entry.translated === null; })) return;
    var groups = new Map();
    page.entries.forEach(function (entry) {
      if (entry.translated.trim() === entry.original.trim()) return;
      var owner = entry.node.parentElement.closest('p,li,td,th,blockquote,h1,h2,h3,h4,h5,h6') || entry.node.parentElement;
      if (groups.has(owner)) return;
      var copy = owner.cloneNode(true);
      // Nested list items/paragraphs get their own source line, never duplicate their code or controls.
      Array.from(copy.querySelectorAll('ul,ol,dl,table,pre,p,li,blockquote,h1,h2,h3,h4,h5,h6,button,input,textarea,select')).forEach(function (node) { node.remove(); });
      var text = copy.textContent.trim();
      if (text) groups.set(owner, text);
    });
    page.entries.forEach(function (entry) { entry.node.data = entry.translated; });
    var translated = page.root.innerHTML;
    groups.forEach(function (text, parent) {
      var source = document.createElement('span');
      source.setAttribute('data-ghcn-original', '');
      source.setAttribute('translate', 'no');
      source.style.cssText = 'display:block;opacity:.68;font-size:.9em;white-space:pre-wrap;margin-bottom:.18em;';
      source.textContent = text;
      parent.insertBefore(source, parent.firstChild);
    });
    page.ready = { translated: translated, bilingual: page.root.innerHTML };
    remember(page.key, page.ready);
    nativeRender(page, bilingual ? page.ready.bilingual : page.ready.translated);
  }
  function prepareNative(receiver, original, args, postRender) {
    var signature = JSON.stringify(args);
    if (nativePage && nativePage.signature === signature && nativePage.language === target) {
      // GitHub rebinds the same HTML after scrolling. Never write its original over a ready translation.
      return;
    }
    epoch++;
    var page = nativePage = { serial: ++nativeSequence, receiver: receiver, original: original,
      args: args.slice(), signature: signature, language: target, key: cacheKey(args[1]),
      entries: [], ready: null, started: Date.now(), displayed: null, afterLoad: postRender || null };
    if (!enabled) { nativeRender(page, args[1]); return; }
    var cached = pageCache.get(page.key);
    if (cached) {
      pageCache.delete(page.key); pageCache.set(page.key, cached);
      page.ready = cached;
      nativeRender(page, bilingual ? cached.bilingual : cached.translated);
      return;
    }
    page.root = document.createElement('main');
    page.root.className = 'markdown-body'; page.root.innerHTML = args[1];
    var walker = document.createTreeWalker(page.root, NodeFilter.SHOW_TEXT), node;
    while ((node = walker.nextNode())) if (eligible(node)) page.entries.push({ id: ++sequence,
      node: node, original: node.data, translated: null, pending: null, retryAt: 0, error: null });
    // Translate the detached document, not the visible original. Code, links and controls are untouched.
    var content = document.getElementById('content');
    if (content) {
      content.innerHTML = '<p data-ghcn-loading="" aria-live="polite" style="opacity:.65;padding:12px 0">正在准备整页译文…</p>';
      receiver._id = args[0]; updateHeight();
    }
    nativeReady(page);
  }
  function install() {
    if (!local() || !window.github || typeof window.github.load !== 'function' || window.github.load.__ghcnWrapped) return;
    var original = window.github.load;
    var wrapped = function () {
      var args = Array.prototype.slice.call(arguments);
      if (typeof args[1] !== 'string') return original.apply(this, args);
      prepareNative(this, original, args);
    };
    wrapped.__ghcnWrapped = true; window.github.load = wrapped;
  }
  function configure(on, language, configRevision, dual) {
    on = !!on && language !== 'en';
    var changed = on !== enabled || language !== target || address !== window.location.href;
    var revised = typeof configRevision === 'number' && configRevision !== revision;
    var modeChanged = bilingual !== !!dual;
    if (nativePage) {
      var page = nativePage;
      if (changed) {
        var postRender = address === window.location.href ? page.afterLoad : null;
        enabled = on; target = language; bilingual = !!dual;
        nativePage = null;
        prepareNative(page.receiver, page.original, page.args, postRender);
      } else if (modeChanged && page.ready) {
        bilingual = !!dual;
        nativeRender(page, bilingual ? page.ready.bilingual : page.ready.translated);
      }
      if (revised && nativePage && !nativePage.ready) {
        epoch++; nativePage.entries.forEach(function (entry) { entry.pending = null; });
      }
    } else if (changed || revised) {
      restore(); epoch++;
    }
    enabled = on; target = language; bilingual = !!dual; address = window.location.href;
    if (typeof configRevision === 'number') revision = configRevision;
    install();
  }
  function nativePoll() {
    var page = nativePage, items = [], translated = 0, errors = 0, characters = 0, now = Date.now();
    page.entries.forEach(function (entry) {
      if (entry.translated !== null) translated++;
      if (entry.error) errors++;
      if (!enabled || page.ready || page.suspended || entry.translated !== null || entry.retryAt > now || items.length >= 32) return;
      if (entry.pending && now - entry.pending.time < 120000) return;
      if (items.length && characters + entry.original.length > 48000) return;
      entry.pending = { epoch: epoch, time: now }; characters += entry.original.length;
      items.push({ id: entry.id, text: entry.original.trim() });
    });
    if (!page.ready && !page.suspended && (errors || now - page.started > 15000)) {
      var content = document.getElementById('content'), message = content && content.querySelector('[data-ghcn-loading]');
      if (message && !message.querySelector('button')) {
        message.textContent = '整页翻译尚未完成，请检查模型或网络。 ';
        var original = document.createElement('button'); original.textContent = '先查看原文';
        original.onclick = function () { page.suspended = true; nativeRender(page, page.args[1]); };
        message.appendChild(original);
        var retry = document.createElement('button'); retry.textContent = '重试';
        retry.onclick = function () {
          epoch++; page.started = Date.now();
          page.entries.forEach(function (entry) { entry.error = null; entry.retryAt = 0; entry.pending = null; });
          message.textContent = '正在准备整页译文…';
        };
        message.appendChild(retry); updateHeight();
      }
    }
    var exported = null;
    if (page.ready && !page.exported) {
      page.exported = true;
      exported = { source: page.args[1], translated: page.ready.translated, bilingual: page.ready.bilingual };
    }
    return { allowed: true, document: documentKey(), epoch: epoch, target: target, items: items, cache: exported,
      translated: translated, total: page.entries.length, errors: errors, native: true, ready: !!page.ready || !!page.suspended };
  }

  window.__ghcnImmersive = {
    version: 2,
    configure: configure,
    install: install,
    afterLoad: afterLoad,
    seed: function (cached) {
      if (local() && cached && typeof cached.source === 'string' && typeof cached.translated === 'string' && typeof cached.bilingual === 'string')
        remember(cacheKey(cached.source), { translated: cached.translated, bilingual: cached.bilingual });
    },
    poll: function (on, language, configRevision, dual) {
      if (!trusted()) return { allowed: false };
      observeRoot();
      configure(on, language, configRevision, dual);
      if (nativePage) return nativePoll();
      if (dirty) scan();
      var items = [], translated = 0, failed = 0, now = Date.now();
      entries.forEach(function (e) {
        if (e.translated !== null) translated++;
        if (e.error) failed++;
        if (!enabled || items.length >= 8 || e.translated !== null || e.retryAt > now ||
            !document.contains(e.node) || !eligible(e.node)) return;
        if (e.pending && now - e.pending.time < 120000) return;
        // A timed-out request can still return while its retry is in flight.
        if (e.pending) { entries.delete(e.id); e.id = ++sequence; entries.set(e.id, e); }
        e.pending = { epoch: epoch, source: e.node.data, time: now };
        items.push({ id: e.id, text: e.node.data.trim() });
      });
      return { allowed: true, document: documentKey(), epoch: epoch, target: target,
        items: items, translated: translated, total: entries.size, errors: failed };
    },
    apply: function (page, expectedEpoch, id, text, error) {
      if (!trusted() || !enabled || page !== documentKey() || expectedEpoch !== epoch) return false;
      if (nativePage) {
        var item = nativePage.entries.find(function (entry) { return entry.id === id; });
        if (!item || !item.pending || item.pending.epoch !== epoch) return false;
        item.pending = null;
        if (error || typeof text !== 'string' || !text.trim()) {
          item.error = error || 'empty translation'; item.retryAt = Date.now() + 60000; return false;
        }
        item.translated = item.original.match(/^\s*/)[0] + text.trim() + item.original.match(/\s*$/)[0];
        item.error = null; nativeReady(nativePage); return true;
      }
      var e = entries.get(id);
      if (!e || !e.pending || e.pending.epoch !== epoch || !document.contains(e.node)) return false;
      if (e.node.data !== e.pending.source || !eligible(e.node)) {
        e.pending = null; dirty = true; return false;
      }
      e.pending = null;
      if (error || typeof text !== 'string' || !text.trim()) {
        e.error = error || 'empty translation'; e.retryAt = Date.now() + 60000; return false;
      }
      var leading = e.original.match(/^\s*/)[0], trailing = e.original.match(/\s*$/)[0];
      e.translated = leading + text.trim() + trailing;
      e.node.data = e.translated; e.error = null;
      updateHeight();
      return true;
    },
    applyBatch: function (page, expectedEpoch, results) {
      var api = this, accepted = 0;
      results.forEach(function (result) {
        if (api.apply(page, expectedEpoch, result.id, result.text, result.error)) accepted++;
      });
      return accepted;
    }
  };
})();
