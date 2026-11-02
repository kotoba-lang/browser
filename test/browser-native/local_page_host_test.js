import { commitLocalPageProjection } from '../../src/browser/local_page_host.js';
import { commitLocalProjection } from '../../src/browser/local_dom_host.js';
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
import batches from './page_fixture.js';
import nativeFrame from './page_frame_fixture.js';
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
  const shellWire = JSON.stringify(shell), shellBytes = encoder.encode(shellWire).length;
  const normal = encoder.encode(`FRAME 1 ${shellBytes}\n${shellWire}`);
  const navFrame = (metadata, page = '') => {
    const json = typeof metadata === 'string' ? metadata : JSON.stringify(metadata);
    return encoder.encode(`NFRAME 2 ${shellBytes} ${encoder.encode(page).length} ${encoder.encode(json).length}\n${shellWire}${page}${json}`);
  };
  {
    const div = document.createElement('div'); let rejected = false, notified = false;
    const host = createLocalActorHost({mount:div, write:() => {}, onNavigation:() => {notified = true;}, onError:() => {rejected = true;}});
    const item = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    try { host.output(1, encoder.encode(`NFRAME 1 ${shellBytes} 0 ${encoder.encode(item).length}\n${shellWire}${item}`)); } catch (_) {}
    if (!rejected || notified || div.childNodes.length) throw new Error('unsolicited navigation accepted');
  }
  for (const page of ['', JSON.stringify(batches)]) {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [], writes = [];
    const host = createLocalActorHost({mount:div, write:x => writes.push(x), onNavigation:x => notices.push(x)});
    host.output(1, normal);
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    const data = navFrame({tab:'tab-1', generation:3, url:'https://one.example/'}, page);
    for (let i = 0; i < data.length; i += 13) host.output(1, data.slice(i, i + 13));
    if (writes.length !== 1 || notices.length !== 1 || !Object.isFrozen(notices[0]) ||
        JSON.stringify(notices[0]) !== JSON.stringify({revision:2, tab:'tab-1', generation:3, url:'https://one.example/'})) {
      throw new Error('actor navigation notification mismatch');
    }
    let forged = false;
    try { host.cancelNavigation({...notices[0]}); } catch (_) { forged = true; }
    if (!forged || !host.cancelNavigation(notices[0]) || !host.state().waiting ||
        writes[1] !== '12\nC1|2|3|tab-1' || host.cancelNavigation(notices[0]) !== false) {
      throw new Error('navigation cancellation mismatch');
    }
    const response = page ? encoder.encode(`PFRAME 3 ${shellBytes} ${encoder.encode(page).length}\n${shellWire}${page}`)
                          : encoder.encode(`FRAME 3 ${shellBytes}\n${shellWire}`);
    host.output(1, response);
    let replay = false;
    try { host.cancelNavigation(notices[0]); } catch (_) { replay = true; }
    if (!replay || host.state().revision !== 3 || host.state().waiting) throw new Error('cancel replay accepted');
    host.close(); div.remove();
  }
  for (const invalid of ['{', {tab:'tab-1', generation:0, url:'https://one.example/'},
                         {tab:'tab-1', generation:1, url:'https://one.example/', approval:true}]) {
    const div = document.createElement('div'); document.body.appendChild(div);
    let error = false, notified = false;
    const host = createLocalActorHost({mount:div, write:() => {}, onNavigation:() => {notified = true;}, onError:() => {error = true;}});
    host.output(1, normal);
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    const stable = div.firstChild;
    try { host.output(1, navFrame(invalid)); } catch (_) {}
    if (!error || notified || !host.state().closed || div.firstChild !== stable) throw new Error('invalid navigation accepted');
    div.remove();
  }
  {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [];
    const host = createLocalActorHost({mount:div, write:() => {}, onNavigation:x => notices.push(x)});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const item = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    const bytes = encoder.encode(withTab).length;
    host.output(1, encoder.encode(`FRAME 1 ${bytes}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${bytes} 0 ${encoder.encode(item).length}\n${withTab}${item}`));
    div.querySelector('input').dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'x'}));
    host.output(1, encoder.encode(`FRAME 3 ${shellBytes}\n${shellWire}`));
    let rejected = false;
    try { host.cancelNavigation(notices[0]); } catch (_) { rejected = true; }
    if (!rejected) throw new Error('closed tab navigation handle remained valid');
    host.close(); div.remove();
  }
  if (nativeFrame !== null) {
    const nativeMount = document.createElement('div'); document.body.appendChild(nativeMount);
    let nativePage = null;
    const attachNative = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function(options) { const shadow = attachNative.call(this, options); nativePage = shadow; return shadow; };
    try {
      const native = createLocalActorHost({mount:nativeMount, write:() => {}});
      const bytes = Uint8Array.from(nativeFrame);
      for (let i = 0; i < bytes.length; i += 19) native.output(1, bytes.slice(i, i + 19));
      if (native.state().revision !== 1 || !nativePage || !nativePage.firstChild.isEqualNode(expected) ||
          nativeMount.querySelector('input')?.value !== 'https://one.example/') throw new Error('native page frame mismatch');
      native.close();
    } finally { Element.prototype.attachShadow = attachNative; nativeMount.remove(); }
  }
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
