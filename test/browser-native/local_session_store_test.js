import { createLocalSessionStore } from '../../src/browser/local_session_store.js';
import { createLocalSessionPersistence } from '../../src/browser/local_session_persistence.js';
const profile = new URL(location.href).searchParams.get('profile');
const store = createLocalSessionStore(profile);
let current = null;
async function tamper() {
  const db = await new Promise((resolve, reject) => {
    const req = indexedDB.open('kotoba-browser-session-v1-' + profile, 1);
    req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
  });
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction('records', 'readwrite'), bucket = tx.objectStore('records');
      const req = bucket.get('current');
      req.onsuccess = () => { const changed = req.result; changed.payload += '!'; bucket.put(changed, 'current'); };
      tx.oncomplete = resolve; tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
async function tamperUndefined() {
  const db = await new Promise((resolve, reject) => {
    const req = indexedDB.open('kotoba-browser-session-v1-' + profile, 1);
    req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
  });
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction('records', 'readwrite');
      tx.objectStore('records').put(undefined, 'current');
      tx.oncomplete = resolve; tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
window.sessionFixture = {
  async first(payload) {
    if (await store.load() !== null) throw new Error('new profile was not empty');
    const first = await store.save(payload, null);
    if (first.revision !== 1 || (await createLocalSessionStore(profile + '-other').load()) !== null) throw new Error('initial save or isolation failed');
    const otherHandle = createLocalSessionStore(profile);
    const attempts = await Promise.allSettled([store.save(payload, first), otherHandle.save(payload, first)]);
    if (attempts.filter(x => x.status === 'fulfilled').length !== 1 || attempts.filter(x => x.status === 'rejected').length !== 1) throw new Error('concurrent stale write accepted');
    current = await store.load();
    if (current.revision !== 2 || current.payload !== payload) throw new Error('winning save not retained');
    for (const value of ['', '\ud800', 'x'.repeat(131073)]) {
      let rejected = false;
      try { await store.save(value, current); } catch (_) { rejected = true; }
      if (!rejected) throw new Error('invalid payload accepted');
    }
    let getterTouched = false, getterRejected = false;
    const forged = {...current};
    Object.defineProperty(forged, 'revision', {enumerable:true, get() { getterTouched = true; return 2; }});
    try { await store.save('bad', forged); } catch (_) { getterRejected = true; }
    if (!getterRejected || getterTouched) throw new Error('accessor record accepted');
    let absent = false;
    try { await store.save('bad'); } catch (_) { absent = true; }
    if (!absent || JSON.stringify(await store.load()) !== JSON.stringify(current)) throw new Error('invalid write changed stored value');
    const persistenceStore = createLocalSessionStore(profile + '-persist');
    let actorRevision = 1, actorWire = 'BSS1|first', restored = null, changedDuringSave = false;
    const actor = {state:() => ({revision:actorRevision, waiting:false, closed:false}),
      requestSnapshot:async () => ({revision:actorRevision, wire:actorWire}),
      restoreSnapshot:async wire => { if (actorRevision !== 1) throw new Error('not pristine'); restored = wire; actorRevision++; }};
    const persistence = createLocalSessionPersistence({host:actor, store:persistenceStore});
    if (await persistence.restore() !== false) throw new Error('empty persistence restored');
    const saved = await persistence.save();
    if (saved.stale || saved.revision !== 1 || saved.storeRevision !== 1) throw new Error('persistence save mismatch');
    if (!await persistence.restore() || restored !== actorWire || actorRevision !== 2) throw new Error('persistence restore mismatch');
    if (!await persistence.restore().then(() => false, () => true)) throw new Error('non-pristine restore accepted');
    const delayedStore = {load:() => persistenceStore.load(), save:async (...args) => {
      const result = await persistenceStore.save(...args); actorRevision++; changedDuringSave = true; return result;
    }};
    const stale = await createLocalSessionPersistence({host:actor, store:delayedStore}).save();
    if (!changedDuringSave || !stale.stale || stale.revision !== 2 || stale.storeRevision !== 2) throw new Error('stale persistence save missed');
    return current;
  },
  async afterRestart(expected) {
    const persisted = await createLocalSessionStore(profile + '-persist').load();
    if (!persisted || persisted.payload !== 'BSS1|first' || persisted.revision !== 2) throw new Error('persistence restart mismatch');
    const restored = await store.load();
    if (JSON.stringify(restored) !== JSON.stringify(expected)) throw new Error('restart value mismatch');
    await tamper();
    let corrupted = false, conflict = false;
    try { await store.load(); } catch (_) { corrupted = true; }
    try { await store.save('replacement', restored); } catch (_) { conflict = true; }
    if (!corrupted || !conflict) throw new Error('corruption accepted or overwritten');
    await tamperUndefined();
    let undefinedRejected = false, missingWriteRejected = false;
    try { await store.load(); } catch (_) { undefinedRejected = true; }
    try { await store.save('overwrite', null); } catch (_) { missingWriteRejected = true; }
    if (!undefinedRejected || !missingWriteRejected) throw new Error('stored undefined treated as absent');
    return {corrupted, conflict, undefinedRejected, missingWriteRejected, revision:restored.revision, payload:restored.payload};
  },
};
document.getElementById('result').textContent = 'READY';
