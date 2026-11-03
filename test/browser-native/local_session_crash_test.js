// Test-only crash probe. Debugger suspension holds the IndexedDB transaction
// open immediately after put is queued, before its event handler can return.
import { createLocalSessionStore } from '../../src/browser/local_session_store.js';
const store = createLocalSessionStore('crash-recovery');
let interrupt = false;
const put = IDBObjectStore.prototype.put;
IDBObjectStore.prototype.put = function(...args) {
  const result = put.apply(this, args);
  if (interrupt) { interrupt = false; debugger; }
  return result;
};
window.crashFixture = {
  async load() { return store.load(); },
  async save(payload, previous) { return store.save(payload, previous); },
  interruptSave(payload, previous) {
    interrupt = true;
    store.save(payload, previous).catch(() => {});
  },
};
