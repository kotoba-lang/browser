// Explicit, user-side session persistence. The caller owns host authentication
// and decides when to save or restore; no operation here performs navigation.
export function createLocalSessionPersistence({host, store}) {
  if (!host || typeof host.requestSnapshot !== 'function' || typeof host.restoreSnapshot !== 'function' ||
      typeof host.state !== 'function' || !store || typeof store.load !== 'function' || typeof store.save !== 'function') {
    throw new Error('invalid session persistence');
  }
  return Object.freeze({
    async restore() {
      const record = await store.load();
      if (record === null) return false;
      await host.restoreSnapshot(record.payload);
      return true;
    },
    async save() {
      const previous = await store.load();
      const snapshot = await host.requestSnapshot();
      const record = await store.save(snapshot.wire, previous);
      const state = host.state();
      return Object.freeze({revision:snapshot.revision, storeRevision:record.revision,
                            stale:state.closed || state.waiting || state.revision !== snapshot.revision});
    },
  });
}
