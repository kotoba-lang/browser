// Test bridge only: application state and revisions remain in the native actor.
import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from '../../src/browser/local_dom_host.js';
const mount = document.getElementById('mount');
let revision = 0;
let unbind;
const events = [];
window.nativeLive = {
  present(next, batches) {
    if (next !== revision + 1) throw new Error('unexpected revision');
    if (unbind) unbind();
    const projection = commitLocalProjection(mount, batches);
    revision = next;
    unbind = bindLocalInput(mount, projection, message => {
      events.push({ revision, wire: encodeLocalEvent(message) });
    });
  },
  take() { return events.shift() || null; },
  snapshot() {
    const input = mount.querySelector('input');
    return { revision, value: input?.value ?? null,
      focused: document.activeElement === input,
      start: input?.selectionStart ?? null, end: input?.selectionEnd ?? null };
  },
};
