import { commitLocalPageProjection } from '../../src/browser/local_page_host.js';
import batches from './page_fixture.js';
const result = document.getElementById('result');
try {
  const mount = document.getElementById('mount');
  const root = mount.attachShadow({ mode: 'closed' });
  commitLocalPageProjection(root, batches);
  const expected = document.createElement('div');
  expected.innerHTML = '<section><h1>Hi <em>世界</em></h1><p><strong>重要</strong> <code>x</code></p></section>';
  if (!root.firstChild || !root.firstChild.isEqualNode(expected) || mount.shadowRoot !== null) throw new Error('page mismatch');
  const attributed = batches.map(batch => batch.map(op => op.slice()));
  attributed[0].push([':dom/set-attr', 1, ':id', 'page'], [':dom/set-attr', 1, ':class', 'document'], [':dom/set-attr', 1, ':title', '説明']);
  commitLocalPageProjection(root, attributed);
  if (root.firstChild.id !== 'page' || root.firstChild.className !== 'document' || root.firstChild.title !== '説明') throw new Error('page attributes mismatch');
  commitLocalPageProjection(root, batches);
  const openRoot = document.createElement('div').attachShadow({mode:'open'});
  let openRefused = false;
  try { commitLocalPageProjection(openRoot, batches); } catch (_) { openRefused = true; }
  if (!openRefused || openRoot.childNodes.length) throw new Error('open page root accepted');
  const before = root.firstChild;
  const refuse = ops => {
    let rejected = false;
    try { commitLocalPageProjection(root, ops); } catch (_) { rejected = true; }
    if (!rejected || root.firstChild !== before) throw new Error('invalid page committed');
  };
  refuse([[[':dom/create-element', 1, ':script'], [':dom/set-root', 1]]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/set-attr', 1, ':onclick', 'alert(1)']]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/set-attr', 1, ':href', 'https://example.com']]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-element', 2, ':br'], [':dom/append-child', 1, 2], [':dom/create-text', 3, 'x'], [':dom/append-child', 2, 3]]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-element', 2, ':p']]]);
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
