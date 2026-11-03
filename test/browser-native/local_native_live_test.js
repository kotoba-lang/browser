// Test bridge only: application state and revisions remain in the native actor.
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
const mount = document.getElementById('mount');
const events = [];
let busy = 0;
let capturedNode = null;
const host = createLocalActorHost({ mount, write: input => events.push(input), onBusy: () => busy++ });
window.nativeLive = {
  feed(bytes) { host.output(1, Uint8Array.from(bytes)); },
  take() { return events.shift() || null; },
  busy() { return busy; },
  captureNode() { capturedNode = mount.querySelector('input'); },
  sameNode() { return capturedNode === mount.querySelector('input'); },
  refusedClick(first) {
    const div = document.createElement('div'); document.body.appendChild(div);
    const writes = []; let busy = 0, errors = 0;
    const instance = createLocalActorHost({mount:div, write:s => writes.push(s), onBusy:() => busy++, onError:() => errors++});
    instance.output(1, Uint8Array.from(first));
    const button = div.querySelector('button');
    button.click();
    instance.output(1, new TextEncoder().encode('REJECT\n'));
    const intact = !instance.state().closed && !instance.state().waiting && instance.state().revision === 1 && busy === 1;
    button.click();
    const ok = intact && instance.state().waiting && writes.length === 2 && errors === 0;
    instance.close(); div.remove(); return ok;
  },
  queueLimit(first, second) {
    const div = document.createElement('div'); document.body.appendChild(div);
    const writes = []; let refused = 0;
    const instance = createLocalActorHost({mount:div, write:s => writes.push(s), onBusy:() => refused++});
    instance.output(1, Uint8Array.from(first));
    div.querySelector('button').click();
    instance.output(1, Uint8Array.from(second));
    const input = div.querySelector('input'); input.focus();
    for (let i = 0; i < 34; i++) input.dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'x'}));
    input.dispatchEvent(new InputEvent('beforeinput', {bubbles:true, cancelable:true, inputType:'insertText', data:'x'.repeat(65537)}));
    const pointer = new PointerEvent('pointerdown', {bubbles:true, cancelable:true});
    input.dispatchEvent(pointer);
    const ok = writes.length === 2 && refused === 3 && pointer.defaultPrevented && instance.state().waiting;
    instance.close(); div.remove(); return ok;
  },
  compositionCleanup(first) {
    return ['close', 'error'].every(mode => {
      const div = document.createElement('div'); document.body.appendChild(div);
      const writes = []; let errors = 0;
      const instance = createLocalActorHost({mount:div, write:s => writes.push(s), onError:() => errors++});
      instance.output(1, Uint8Array.from(first));
      const input = div.querySelector('input'); input.focus();
      input.dispatchEvent(new CompositionEvent('compositionstart', {bubbles:true}));
      if (mode === 'close') instance.close();
      else { try { instance.output(1, new TextEncoder().encode('bad\n')); } catch (_) {} }
      const written = writes.length;
      input.dispatchEvent(new CompositionEvent('compositionend', {bubbles:true, data:'語'}));
      let reused = false;
      try {
        const next = createLocalActorHost({mount:div, write:() => {}});
        next.output(1, Uint8Array.from(first));
        reused = true;
        next.close();
      } catch (_) {}
      const ok = instance.state().closed && writes.length === written && reused &&
        (mode === 'error' ? errors === 1 : errors === 0 && writes.at(-1) === '0\n');
      div.remove(); return ok;
    });
  },
  negative(first) {
    const enc = new TextEncoder();
    const cases = [enc.encode('REJECT\n'), enc.encode('FRAME 2 2\n[]'),
      enc.encode('FRAME 1 1048577\n'), Uint8Array.of(70,82,65,77,69,32,49,32,49,10,255),
      enc.encode('FRAME 1 1\n{')];
    return cases.every(bytes => {
      const div = document.createElement('div'); document.body.appendChild(div);
      let error = false, wrote = false;
      const instance = createLocalActorHost({mount:div, write:() => { wrote = true; }, onError:() => { error = true; }});
      try { instance.output(1, bytes); } catch (_) {}
      const ok = error && instance.state().closed && !wrote && div.childNodes.length === 0;
      div.remove(); return ok;
    }) && (() => {
      const div = document.createElement('div'); document.body.appendChild(div);
      let error = false;
      const instance = createLocalActorHost({mount:div, write:() => {}, onError:() => { error = true; }});
      instance.output(1, Uint8Array.from(first));
      try { instance.output(1, Uint8Array.from(first)); } catch (_) {}
      const ok = error && instance.state().closed && instance.state().revision === 1;
      div.remove(); return ok;
    })();
  },
  snapshot() {
    const input = mount.querySelector('input');
    return { revision: host.state().revision, value: input?.value ?? null,
      focused: document.activeElement === input,
      start: input?.selectionStart ?? null, end: input?.selectionEnd ?? null };
  },
};
