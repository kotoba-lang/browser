// User-side IndexedDB storage for an opaque, already-validated session snapshot.
// This module does not parse the snapshot, authorize operations, fetch or
// restart the actor. Checksums detect accidental corruption, not tampering.
const PREFIX = 'kotoba-browser-session-v1-';
const LIMIT = 131072;
const FIELDS = ['digest', 'payload', 'revision', 'version'];
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', {fatal:true});

function payloadBytes(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > LIMIT) throw new Error('invalid snapshot payload');
  const bytes = encoder.encode(value);
  if (bytes.length > LIMIT || decoder.decode(bytes) !== value) throw new Error('invalid snapshot payload');
  return bytes;
}
async function digest(bytes) {
  const result = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(result, byte => byte.toString(16).padStart(2, '0')).join('');
}
async function checked(record) {
  if (!record || typeof record !== 'object' || Array.isArray(record) ||
      (Object.getPrototypeOf(record) !== Object.prototype && Object.getPrototypeOf(record) !== null) ||
      Reflect.ownKeys(record).sort().join(',') !== FIELDS.join(',')) throw new Error('invalid session record');
  // Reject accessors and snapshot the four data properties before awaiting.
  const descriptors = FIELDS.map(key => Object.getOwnPropertyDescriptor(record, key));
  if (descriptors.some(value => !value || !Object.hasOwn(value, 'value'))) throw new Error('invalid session record');
  const copy = {digest:descriptors[0].value, payload:descriptors[1].value,
                revision:descriptors[2].value, version:descriptors[3].value};
  if (copy.version !== 1 || !Number.isSafeInteger(copy.revision) || copy.revision < 1 ||
      typeof copy.digest !== 'string' || !/^[0-9a-f]{64}$/.test(copy.digest)) throw new Error('invalid session record');
  if (await digest(payloadBytes(copy.payload)) !== copy.digest) throw new Error('session record checksum mismatch');
  return Object.freeze(copy);
}
function same(a, present, b) {
  if (b === null) return !present;
  return present && a && typeof a === 'object' && !Array.isArray(a) && Object.keys(a).sort().join(',') === FIELDS.join(',') &&
    a.version === b.version && a.revision === b.revision && a.payload === b.payload && a.digest === b.digest;
}
function open(name) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    let settled = false;
    request.onupgradeneeded = () => { request.result.createObjectStore('records'); };
    request.onerror = () => { if (!settled) { settled = true; reject(request.error || new Error('session database open failed')); } };
    request.onblocked = () => { if (!settled) { settled = true; reject(new Error('session database blocked')); } };
    request.onsuccess = () => { if (settled) request.result.close(); else { settled = true; resolve(request.result); } };
  });
}
function transaction(db, mode, action) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('records', mode), store = tx.objectStore('records');
    let result, failure;
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(failure || tx.error || new Error('session transaction aborted'));
    tx.onerror = () => { failure ||= tx.error || new Error('session transaction failed'); };
    try { action(store, value => {result = value;}, error => {failure = error; tx.abort();}); }
    catch (error) { failure = error; tx.abort(); }
  });
}
export function createLocalSessionStore(profile) {
  if (typeof profile !== 'string' || !/^[a-z0-9_-]{1,32}$/.test(profile)) throw new Error('invalid session profile');
  const name = PREFIX + profile;
  return Object.freeze({
    async load() {
      const db = await open(name);
      try {
        const record = await transaction(db, 'readonly', (store, done) => {
          const value = store.get('current'), count = store.count('current');
          count.onsuccess = () => done({present:count.result === 1, value:value.result});
        });
        return record.present ? await checked(record.value) : null;
      } finally { db.close(); }
    },
    async save(payload, expected) {
      if (expected === undefined) throw new Error('expected session version required');
      const bytes = payloadBytes(payload);
      const previous = expected === null ? null : await checked(expected);
      if (previous && previous.revision === Number.MAX_SAFE_INTEGER) throw new Error('session revision exhausted');
      const next = Object.freeze({version:1, revision:previous ? previous.revision + 1 : 1,
                                  payload, digest:await digest(bytes)});
      const db = await open(name);
      try {
        await transaction(db, 'readwrite', (store, done, abort) => {
          const value = store.get('current'), count = store.count('current');
          count.onsuccess = () => {
            if (!same(value.result, count.result === 1, previous)) return abort(new Error('session write conflict'));
            store.put(next, 'current'); done(next);
          };
        });
        return next;
      } finally { db.close(); }
    },
  });
}
