(function () {
  'use strict';
  if (window.__ghcnImmersive && window.__ghcnImmersive.version === 1) return;

  var originals = new WeakMap(), entries = new Map(), sequence = 0;
  var token = Date.now().toString(36) + Math.random().toString(36).slice(2);
  var epoch = 0, enabled = false, target = '', dirty = true;
  var address = window.location.href, revision = -1;
  var heightQueued = false;
  var skip = 'pre,code,kbd,samp,script,style,svg,input,textarea,select,button,[contenteditable="true"],[translate="no"],.notranslate';

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
  function documentKey() { return token + '|' + window.location.href; }
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
        if (!eligible(node)) continue;
        var e = originals.get(node);
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
  var observer = new MutationObserver(function () { dirty = true; });
  if (document.documentElement) observer.observe(document.documentElement,
    { subtree: true, childList: true, characterData: true });

  window.__ghcnImmersive = {
    version: 1,
    poll: function (on, language, configRevision) {
      if (!trusted()) return { allowed: false };
      on = !!on && language !== 'en';
      if (on !== enabled || language !== target || address !== window.location.href ||
          (typeof configRevision === 'number' && configRevision !== revision)) {
        restore(); epoch++; enabled = on; target = language;
        address = window.location.href;
        if (typeof configRevision === 'number') revision = configRevision;
      }
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
    }
  };
})();
