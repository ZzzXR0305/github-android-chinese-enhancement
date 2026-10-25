'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(__dirname + '/../payload/assets/github-immersive.js', 'utf8');

// A small DOM fixture keeps this check runnable with Node and no installed dependencies.
function page(url, localTemplate = false, options = {}) {
  let heights = 0, now = Date.now();
  const observers = new Set();
  function makeTree() {
    const nodes = [];
    const root = { nodes, contains: node => nodes.includes(node) };
    return { nodes, root, element: { contains: node => node === root || nodes.includes(node) } };
  }
  let tree = makeTree();
  const location = new URL(url);
  const document = {
    documentElement: options.missingRoot ? null : tree.element,
    querySelectorAll: () => document.documentElement ? [tree.root] : [],
    querySelector: () => localTemplate && document.documentElement ? tree.root : null,
    contains: node => !!document.documentElement && tree.nodes.includes(node),
    createTreeWalker: root => {
      let index = 0;
      return { nextNode: () => root.nodes[index++] || null };
    }
  };
  function changed(target, type) {
    for (const observer of observers) {
      for (const [observed, configuration] of observer.targets) {
        const directDocumentChange = observed === document && target === document;
        const inSubtree = configuration.subtree && (observed === target ||
          (observed === document && target === document.documentElement));
        if ((directDocumentChange || inSubtree) && configuration[type]) {
          observer.callback(); break;
        }
      }
    }
  }
  const context = vm.createContext({ window: { location,
    github: { _updateHeight: () => heights++ } }, document,
    NodeFilter: { SHOW_TEXT: 4 },
    Date: class extends Date { static now() { return now; } },
    MutationObserver: class {
      constructor(callback) { this.callback = callback; this.targets = new Map(); observers.add(this); }
      observe(target, configuration) {
        if (!target) throw new TypeError('MutationObserver requires an existing target');
        this.targets.set(target, configuration);
      }
      disconnect() { this.targets.clear(); }
    }
  });
  vm.runInContext(script, context);
  function add(text, tag = 'p', excluded = false) {
    assert.ok(document.documentElement, 'Cannot add fixture content before its root exists');
    const owner = tree;
    let data = text;
    const element = { tagName: tag.toUpperCase(), href: 'https://github.com/example/project',
      closest: () => excluded || /^(pre|code|kbd|samp|script|style|svg|input|textarea|select|button)$/.test(tag) ? element : null };
    const node = { parentElement: element };
    Object.defineProperty(node, 'data', { get: () => data, set: value => { data = value; changed(owner.element, 'characterData'); } });
    owner.nodes.push(node); changed(owner.element, 'childList'); return node;
  }
  return { api: context.window.__ghcnImmersive, add, location,
    heights: () => heights,
    advanceTime: milliseconds => { now += milliseconds; },
    replaceRoot: () => {
      tree = makeTree(); document.documentElement = tree.element;
      // Replacing the HTML node mutates its Document parent, not the old HTML subtree.
      changed(document, 'childList');
    },
    remove: node => {
      const index = tree.nodes.indexOf(node);
      if (index >= 0) { tree.nodes.splice(index, 1); changed(tree.element, 'childList'); }
    } };
}
const p = page('https://appassets.androidplatform.net/android_asset/webview/');
const prose = p.add('  Install this application.\n');
const link = p.add('Read the documentation', 'a');
const code = p.add('npm install example', 'code');
const input = p.add('User draft in an input', 'input');
const noTranslate = p.add('Keep this exact label', 'p', true);
let batch = p.api.poll(true, 'zh');
assert.equal(batch.items.length, 2);
assert.equal(batch.items[0].text, 'Install this application.');
assert.equal(p.api.apply(batch.document, batch.epoch, batch.items[0].id, '安装这个应用。', null), true);
assert.equal(prose.data, '  安装这个应用。\n');
assert.ok(p.heights() > 0, 'text growth must notify the original native height bridge');
assert.equal(code.data, 'npm install example');
assert.equal(input.data, 'User draft in an input');
assert.equal(noTranslate.data, 'Keep this exact label');
assert.equal(link.parentElement.href, 'https://github.com/example/project');
assert.equal(p.api.poll(true, 'zh').items.length, 0, 'pending nodes must not be resubmitted');

const comment = p.add('A newly loaded discussion comment.');
batch = p.api.poll(true, 'zh');
assert.equal(batch.items.length, 1, 'MutationObserver must discover dynamic content');
assert.equal(p.api.apply(batch.document, batch.epoch, batch.items[0].id, '新的讨论评论。', null), true);
p.api.poll(false, 'zh');
assert.equal(prose.data, '  Install this application.\n');
assert.equal(comment.data, 'A newly loaded discussion comment.');

batch = p.api.poll(true, 'ja');
const oldEpoch = batch.epoch;
p.api.poll(true, 'en');
assert.equal(p.api.apply(batch.document, oldEpoch, batch.items[0].id, 'stale result', null), false);
assert.equal(prose.data, '  Install this application.\n', 'English target restores originals');

batch = p.api.poll(true, 'es');
prose.data = 'The site replaced this paragraph.';
assert.equal(p.api.apply(batch.document, batch.epoch, batch.items[0].id, 'stale source', null), false);
batch = p.api.poll(true, 'es');
assert.equal(batch.items[0].text, 'The site replaced this paragraph.');
p.location.href = 'https://appassets.androidplatform.net/android_asset/webview/another';
assert.equal(p.api.apply(batch.document, batch.epoch, batch.items[0].id, 'previous page', null), false);
p.remove(comment);
assert.equal(p.api.poll(true, 'es').total, 2, 'removed DOM nodes must be released');
batch = p.api.poll(true, 'es', 1);
assert.equal(batch.items.length, 2, 'config revision clears canceled pending work');
assert.equal(p.api.poll(true, 'es', 1).items.length, 0);
assert.equal(p.api.poll(true, 'es', 3).items.length, 2, 'quickly toggling off/on must not stall');

const foreign = page('https://example.com');
foreign.add('Never read this foreign website.');
assert.equal(foreign.api.poll(true, 'zh').allowed, false);
const legacy = page('about:blank', true);
legacy.add('Legacy markdown rendering.');
assert.equal(legacy.api.poll(true, 'zh').items.length, 1);
const blank = page('about:blank', false);
blank.add('An unrelated blank page.');
assert.equal(blank.api.poll(true, 'zh').allowed, false);
const race = page('https://appassets.androidplatform.net/android_asset/webview/');
const changed = race.add('The old paragraph before refresh.');
const oldBatch = race.api.poll(true, 'zh');
changed.data = 'The new paragraph after refresh.';
const newBatch = race.api.poll(true, 'zh');
assert.notEqual(newBatch.items[0].id, oldBatch.items[0].id, 'changed source needs a new request identity');
assert.equal(race.api.apply(oldBatch.document, oldBatch.epoch, oldBatch.items[0].id, 'OLD TRANSLATION', null), false);
assert.equal(changed.data, 'The new paragraph after refresh.', 'old callback after new poll cannot overwrite refreshed text');
assert.equal(race.api.apply(newBatch.document, newBatch.epoch, newBatch.items[0].id, '刷新后的新段落。', null), true);
assert.equal(changed.data, '刷新后的新段落。');

// The original renderer may reapply the same English to an existing translated node.
// A warm cache must put its translation back during the next scan, without new work.
const cachedRefresh = page('https://appassets.androidplatform.net/android_asset/webview/');
const cachedSource = 'The same paragraph was refreshed by the original renderer.';
const cachedTranslation = '原始渲染器刷新了同一个段落。';
const cachedNode = cachedRefresh.add(cachedSource);
const cachedRequest = cachedRefresh.api.poll(true, 'zh', 1);
assert.equal(cachedRequest.items.length, 1);
assert.equal(cachedRefresh.api.apply(cachedRequest.document, cachedRequest.epoch,
  cachedRequest.items[0].id, cachedTranslation, null), true);
for (let refresh = 0; refresh < 10; refresh++) {
  cachedNode.data = cachedSource;
  const cachedBatch = cachedRefresh.api.poll(true, 'zh', 1);
  assert.equal(cachedNode.data, cachedTranslation, 'same-node source refresh must immediately reuse its cached translation');
  assert.equal(cachedBatch.items.length, 0, 'warm same-source refresh must not submit new translation requests');
}
const retry = page('https://appassets.androidplatform.net/android_asset/webview/');
retry.add('A delayed translation result.');
const expired = retry.api.poll(true, 'zh');
retry.advanceTime(120001);
const retried = retry.api.poll(true, 'zh');
assert.equal(retried.items.length, 1, 'expired pending work is retried only once');
assert.notEqual(retried.items[0].id, expired.items[0].id);
assert.equal(retry.api.apply(expired.document, expired.epoch, expired.items[0].id, null, 'old failure'), false);
assert.equal(retry.api.apply(retried.document, retried.epoch, retried.items[0].id, '迟到的翻译结果。', null), true);

const lateRoot = page('https://appassets.androidplatform.net/android_asset/webview/', false, { missingRoot: true });
assert.equal(lateRoot.api.poll(true, 'zh').total, 0, 'first poll before HTML exists is empty');
lateRoot.replaceRoot();
lateRoot.add('The HTML root and paragraph were loaded after the first poll.');
const lateBatch = lateRoot.api.poll(true, 'zh');

const replacedRoot = page('https://appassets.androidplatform.net/android_asset/webview/');
replacedRoot.add('A paragraph in the previous HTML root.');
const previousRootBatch = replacedRoot.api.poll(true, 'zh');
assert.equal(previousRootBatch.total, 1);
replacedRoot.replaceRoot();
replacedRoot.add('A new paragraph after replacing the entire HTML root.');
const replacementBatch = replacedRoot.api.poll(true, 'zh');
console.log('Observer lifecycle diagnostics:', JSON.stringify({
  lateRoot: { total: lateBatch.total, items: lateBatch.items.map(item => item.text) },
  replacedRoot: { total: replacementBatch.total, items: replacementBatch.items.map(item => item.text) }
}));
assert.equal(lateBatch.items.length, 1, 'paragraphs loaded after an initially missing root must be discovered automatically');
assert.equal(lateBatch.items[0].text, 'The HTML root and paragraph were loaded after the first poll.');
assert.equal(replacementBatch.total, 1, 'replacing the HTML root releases the previous root entries');
assert.equal(replacementBatch.items.length, 1, 'new paragraphs after HTML-root replacement must be discovered automatically');
assert.equal(replacementBatch.items[0].text, 'A new paragraph after replacing the entire HTML root.');
assert.equal(replacedRoot.api.apply(previousRootBatch.document, previousRootBatch.epoch,
  previousRootBatch.items[0].id, 'OLD ROOT TRANSLATION', null), false, 'detached-root results cannot alter the new document');
console.log('Immersive checks passed: replacement, whitespace, dynamic text, restore, stale results, exclusions, origins.');
