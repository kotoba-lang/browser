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
  const refuse = ops => {
    let rejected = false;
    try { commitLocalPageProjection(root, ops); } catch (_) { rejected = true; }
    if (!rejected || root.firstChild !== before) throw new Error('invalid page committed');
  };
  const listOps = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
    [':dom/create-element',2,':ol'], [':dom/create-element',3,':li'], [':dom/create-text',4,'one'],
    [':dom/append-child',3,4], [':dom/append-child',2,3], [':dom/append-child',1,2]]];
  commitLocalPageProjection(root, listOps);
  if (root.querySelector('ol > li')?.textContent !== 'one') throw new Error('list projection mismatch');
  const semanticOps = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
    [':dom/create-element',2,':p'], [':dom/create-text',3,'one'], [':dom/append-child',2,3], [':dom/append-child',1,2],
    [':dom/create-element',4,':hr'], [':dom/append-child',1,4],
    [':dom/create-element',5,':blockquote'], [':dom/create-element',6,':small'],
    [':dom/create-text',7,'two'], [':dom/append-child',6,7], [':dom/append-child',5,6], [':dom/append-child',1,5]]];
  commitLocalPageProjection(root, semanticOps);
  const semanticExpected = document.createElement('div');
  semanticExpected.innerHTML = '<p>one<hr><blockquote><small>two</small></blockquote>';
  if (!root.firstChild.isEqualNode(semanticExpected)) throw new Error('semantic page projection mismatch');
  const tableOps = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
    [':dom/create-element',2,':table'], [':dom/create-element',3,':tbody'], [':dom/create-element',4,':tr'],
    [':dom/create-element',5,':th'], [':dom/create-text',6,'種類'], [':dom/append-child',5,6],
    [':dom/create-element',7,':td'], [':dom/create-text',8,'猫'], [':dom/append-child',7,8],
    [':dom/append-child',4,5], [':dom/append-child',4,7], [':dom/append-child',3,4],
    [':dom/append-child',2,3], [':dom/append-child',1,2]]];
  commitLocalPageProjection(root, tableOps);
  const tableExpected = document.createElement('div');
  tableExpected.innerHTML = '<table><tr><th>種類</th><td>猫</td></tr></table>';
  if (!root.firstChild.isEqualNode(tableExpected)) throw new Error('table projection mismatch');
  const preOps = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
    [':dom/create-element',2,':pre'], [':dom/create-text',3,'  Code\n  Text'],
    [':dom/append-child',2,3], [':dom/append-child',1,2]]];
  commitLocalPageProjection(root, preOps);
  const preExpected = document.createElement('div');
  preExpected.innerHTML = '<pre>\n  Code\n  Text</pre>';
  if (!root.firstChild.isEqualNode(preExpected) || root.querySelector('pre').textContent !== '  Code\n  Text') throw new Error('pre projection mismatch');
  const commentOps = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
    [':dom/create-text',2,'before'], [':dom/create-comment',3,'猫 & <'], [':dom/create-text',4,'after'],
    [':dom/append-child',1,2], [':dom/append-child',1,3], [':dom/append-child',1,4]]];
  commitLocalPageProjection(root, commentOps);
  const children = root.firstChild.childNodes;
  if (children.length !== 3 || children[0].nodeType !== Node.TEXT_NODE || children[0].data !== 'before' ||
      children[1].nodeType !== Node.COMMENT_NODE || children[1].data !== '猫 & <' ||
      children[2].nodeType !== Node.TEXT_NODE || children[2].data !== 'after' ||
      root.firstChild.textContent !== 'beforeafter') throw new Error('comment projection mismatch');
  commitLocalPageProjection(root, batches);
  const before = root.firstChild;
  {
    const div = document.createElement('div'), shadow = div.attachShadow({mode:'closed'});
    const seen = [];
    const links = [[[':dom/create-element',1,':div'], [':dom/set-root',1],
      [':dom/create-element',2,':a'], [':dom/set-attr',2,':href','javascript:alert(1)'],
      [':dom/create-text',3,'link'], [':dom/append-child',2,3], [':dom/append-child',1,2]]];
    commitLocalPageProjection(shadow, links, value => seen.push(value));
    const a = shadow.querySelector('a');
    if (!a || a.hasAttribute('href') || a.getAttribute('role') !== 'link') throw new Error('link is not inert');
    a.click(); a.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    const tab = new KeyboardEvent('keydown', {key:'Tab', bubbles:true, cancelable:true}); a.dispatchEvent(tab);
    if (tab.defaultPrevented) throw new Error('link trapped keyboard focus');
    if (seen.length) throw new Error('synthetic link gesture accepted');
  }
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-comment', 2, 'x'], [':dom/create-text', 3, 'y'], [':dom/append-child', 2, 3], [':dom/append-child', 1, 2]]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-comment', 2, 42], [':dom/append-child', 1, 2]]]);
  refuse([[[':dom/create-element', 1, ':script'], [':dom/set-root', 1]]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/set-attr', 1, ':onclick', 'alert(1)']]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/set-attr', 1, ':href', 'https://example.com']]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-element', 2, ':br'], [':dom/append-child', 1, 2], [':dom/create-text', 3, 'x'], [':dom/append-child', 2, 3]]]);
  refuse([[[':dom/create-element', 1, ':div'], [':dom/set-root', 1], [':dom/create-element', 2, ':hr'], [':dom/append-child', 1, 2], [':dom/create-text', 3, 'x'], [':dom/append-child', 2, 3]]]);
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
  {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [], writes = [];
    const host = createLocalActorHost({mount:div, write:x => writes.push(x), onNavigation:x => notices.push(x)});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const n = encoder.encode(withTab).length;
    const notice = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    host.output(1, encoder.encode(`FRAME 1 ${n}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(notice).length}\n${withTab}${notice}`));
    function response(body, type = 'text/html; charset=utf-8') {
      const r = new Response(body, {headers:{'content-type':type}});
      Object.defineProperty(r, 'url', {value:'https://one.example/'});
      return r;
    }
    let forged = false, invalid = false;
    try { await host.deliverHtml({...notices[0]}, response('<p>x</p>')); } catch (_) { forged = true; }
    try { await host.deliverHtml(notices[0], response('x', 'text/plain')); } catch (_) { invalid = true; }
    if (!forged || !invalid || writes.length !== 1) throw new Error('unmatched or invalid response accepted');
    if (!await host.deliverHtml(notices[0], response('<p>x</p>')) || writes.length !== 2 ||
        !writes[1].endsWith('H1|2|1|tab-1|20|https://one.example/<p>x</p>') || !host.state().waiting) {
      throw new Error('HTML response delivery mismatch');
    }
    const page = JSON.stringify(batches);
    host.output(1, encoder.encode(`PFRAME 3 ${n} ${encoder.encode(page).length}\n${withTab}${page}`));
    let replay = false;
    try { await host.deliverHtml(notices[0], response('<p>x</p>')); } catch (_) { replay = true; }
    if (!replay || host.state().waiting) throw new Error('HTML response replay accepted');
    host.close(); div.remove();
  }
  {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = []; let error = false;
    const host = createLocalActorHost({mount:div, write:() => {}, onNavigation:x => notices.push(x), onError:() => {error = true;}});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const n = encoder.encode(withTab).length;
    const notice = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    host.output(1, encoder.encode(`FRAME 1 ${n}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(notice).length}\n${withTab}${notice}`));
    const response = new Response('<p>x</p>', {headers:{'content-type':'text/html'}});
    Object.defineProperty(response, 'url', {value:'https://one.example/'});
    await host.deliverHtml(notices[0], response);
    const stable = div.firstChild;
    try { host.output(1, encoder.encode(`FRAME 3 ${n}\n${withTab}`)); } catch (_) {}
    if (!error || !host.state().closed || div.firstChild !== stable) throw new Error('page-less HTML acknowledgement accepted');
    div.remove();
  }
  {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [], refusals = [], writes = [];
    const host = createLocalActorHost({mount:div, write:x => writes.push(x), onNavigation:x => notices.push(x),
                                      onNavigationError:x => refusals.push(x)});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const n = encoder.encode(withTab).length;
    const notice = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    host.output(1, encoder.encode(`FRAME 1 ${n}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(notice).length}\n${withTab}${notice}`));
    const response = new Response('<script>unsupported</script>', {headers:{'content-type':'text/html'}});
    Object.defineProperty(response, 'url', {value:'https://one.example/'});
    await host.deliverHtml(notices[0], response);
    const stable = div.firstChild;
    for (const part of ['RE', 'JECT\n']) host.output(1, encoder.encode(part));
    if (refusals.length !== 1 || refusals[0].operation !== 'response' || refusals[0].notice !== notices[0] ||
        !Object.isFrozen(refusals[0]) || host.state().closed || host.state().waiting || host.state().revision !== 2 ||
        div.firstChild !== stable || !host.cancelNavigation(notices[0])) throw new Error('actor refusal was not recoverable');
    host.output(1, encoder.encode(`FRAME 3 ${n}\n${withTab}`));
    host.close(); div.remove();
  }
  {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [], writes = [];
    const host = createLocalActorHost({mount:div, write:x => writes.push(x), onNavigation:x => notices.push(x)});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const n = encoder.encode(withTab).length;
    const notice = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    host.output(1, encoder.encode(`FRAME 1 ${n}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(notice).length}\n${withTab}${notice}`));
    let cancelled = false;
    const stream = new ReadableStream({cancel() { cancelled = true; }});
    const slow = new Response(stream, {headers:{'content-type':'text/html'}});
    Object.defineProperty(slow, 'url', {value:'https://one.example/'});
    const delivery = host.deliverHtml(notices[0], slow).then(() => false, () => true);
    let duplicate = false;
    try { await host.deliverHtml(notices[0], new Response('x')); } catch (_) { duplicate = true; }
    if (!duplicate || writes.length !== 1) throw new Error('concurrent HTML response accepted');
    div.querySelector('input').dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'x'}));
    host.output(1, encoder.encode(`FRAME 3 ${n}\n${withTab}`));
    const stale = await Promise.race([delivery, new Promise(resolve => setTimeout(() => resolve(false), 500))]);
    if (!stale || !cancelled || writes.length !== 2 || host.state().waiting) throw new Error('stale asynchronous HTML response accepted');
    host.close(); div.remove();
  }
  for (const action of ['close', 'cancel', 'fail']) {
    const div = document.createElement('div'); document.body.appendChild(div);
    const notices = [], writes = [];
    const host = createLocalActorHost({mount:div, write:x => writes.push(x), onNavigation:x => notices.push(x)});
    const withTab = JSON.stringify([[...shell[0], [':dom/create-element',4,':button'],
      [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]]);
    const n = encoder.encode(withTab).length;
    const notice = JSON.stringify({tab:'tab-1', generation:1, url:'https://one.example/'});
    host.output(1, encoder.encode(`FRAME 1 ${n}\n${withTab}`));
    div.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
    host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(notice).length}\n${withTab}${notice}`));
    let cancelled = false;
    const stream = new ReadableStream({cancel() { cancelled = true; }});
    const slow = new Response(stream, {headers:{'content-type':'text/html'}});
    Object.defineProperty(slow, 'url', {value:'https://one.example/'});
    const delivery = host.deliverHtml(notices[0], slow).then(() => 'resolved', () => 'rejected');
    if (action === 'close') host.close();
    else if (action === 'cancel') host.cancelNavigation(notices[0]);
    else { try { host.output(2, new Uint8Array()); } catch (_) {} }
    const outcome = await Promise.race([delivery, new Promise(resolve => setTimeout(() => resolve('timeout'), 500))]);
    if (outcome !== 'rejected' || !cancelled || writes.some(x => x.includes('H1|'))) throw new Error(`pending body not cancelled on ${action}`);
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
