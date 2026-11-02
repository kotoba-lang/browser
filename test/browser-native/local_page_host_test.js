import { commitLocalPageProjection } from '../../src/browser/local_page_host.js';
import { commitLocalProjection } from '../../src/browser/local_dom_host.js';
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
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
  const shellMount = document.createElement('div'); document.body.appendChild(shellMount);
  const shell = [[[':dom/create-element', 1, ':main'], [':dom/set-root', 1],
    [':dom/create-element', 2, ':input'], [':dom/set-attr', 2, ':data-role', 'address'],
    [':dom/set-attr', 2, ':value', 'https://one.example/'], [':dom/set-attr', 2, ':data-selection-start', 2],
    [':dom/set-attr', 2, ':data-selection-end', 4], [':dom/set-attr', 2, ':data-caret', 2],
    [':dom/append-child', 1, 2], [':dom/create-element', 3, ':section'],
    [':dom/set-attr', 3, ':data-role', 'page'], [':dom/append-child', 1, 3]]];
  commitLocalProjection(shellMount, shell);
  const address = shellMount.querySelector('input'); address.focus();
  let stagedRoot = null;
  const attach = Element.prototype.attachShadow;
  Element.prototype.attachShadow = function(options) { const shadow = attach.call(this, options); stagedRoot = shadow; return shadow; };
  try {
    const projected = commitLocalProjection(shellMount, shell, batches);
    const newAddress = shellMount.querySelector('input');
    if (!stagedRoot || !stagedRoot.firstChild.isEqualNode(expected) ||
        shellMount.querySelector('section').shadowRoot !== null || document.activeElement !== newAddress ||
        newAddress.selectionStart !== 2 || newAddress.selectionEnd !== 4 || newAddress.selectionDirection !== 'backward' ||
        projected.resolve(newAddress)?.id !== 2) throw new Error('combined shell/page mismatch');
    const beforeShell = shellMount.firstChild;
    let denied = false;
    try { commitLocalProjection(shellMount, shell, [[[':dom/create-element', 1, ':script']]]); } catch (_) { denied = true; }
    if (!denied || shellMount.firstChild !== beforeShell || document.activeElement !== newAddress) throw new Error('partial combined commit');
    const missing = shell[0].filter(op => op[1] !== 3 && !(op[0] === ':dom/append-child' && op[2] === 3));
    denied = false;
    try { commitLocalProjection(shellMount, [missing], batches); } catch (_) { denied = true; }
    if (!denied || shellMount.firstChild !== beforeShell) throw new Error('missing page mount accepted');
  } finally { Element.prototype.attachShadow = attach; shellMount.remove(); }
  const actorMount = document.createElement('div'); document.body.appendChild(actorMount);
  const writes = []; let actorError = false, latestPage = null;
  const instance = createLocalActorHost({mount:actorMount, write:x => writes.push(x), onError:() => { actorError = true; }});
  const encoder = new TextEncoder();
  const makeFrame = (revision, page) => {
    const shellWire = JSON.stringify(shell), pageWire = JSON.stringify(page);
    return encoder.encode(`PFRAME ${revision} ${encoder.encode(shellWire).length} ${encoder.encode(pageWire).length}\n${shellWire}${pageWire}`);
  };
  const originalAttach = Element.prototype.attachShadow;
  Element.prototype.attachShadow = function(options) { const shadow = originalAttach.call(this, options); latestPage = shadow; return shadow; };
  try {
    const first = makeFrame(1, batches);
    for (let i = 0; i < first.length; i += 17) instance.output(1, first.slice(i, i + 17));
    if (instance.state().revision !== 1 || !latestPage.firstChild.isEqualNode(expected)) throw new Error('page actor frame mismatch');
    const actorInput = actorMount.querySelector('input'); actorInput.focus();
    actorInput.dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'x'}));
    if (writes.length !== 1 || !instance.state().waiting) throw new Error('page actor did not queue input');
    instance.output(1, makeFrame(2, batches));
    if (instance.state().revision !== 2 || !latestPage.firstChild.isEqualNode(expected)) throw new Error('page lost after input');
    const stable = actorMount.firstChild;
    actorMount.querySelector('input').dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'y'}));
    try { instance.output(1, makeFrame(3, [[[':dom/create-element',1,':script']]])); } catch (_) {}
    if (!actorError || !instance.state().closed || actorMount.firstChild !== stable) throw new Error('bad actor page committed');
  } finally { Element.prototype.attachShadow = originalAttach; actorMount.remove(); }
  for (const header of ['PFRAME 1 1 0\n', 'PFRAME 1 900000 900000\n', 'PFRAME 01 1 1\n']) {
    const badMount = document.createElement('div'); let rejected = false;
    const invalid = createLocalActorHost({mount:badMount, write:() => {}, onError:() => { rejected = true; }});
    try { invalid.output(1, encoder.encode(header)); } catch (_) {}
    if (!rejected || !invalid.state().closed || badMount.childNodes.length) throw new Error('invalid actor page header accepted');
  }
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
