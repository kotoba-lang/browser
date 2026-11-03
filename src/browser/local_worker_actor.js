// Composition boundary for an already verified Wasm module and an owned,
// trusted Amu Worker. Neither this adapter nor stdio authenticates the Worker,
// verifies artifact provenance, fetches, or grants a service operation.
import { createLocalActorHost } from './local_actor_host.js';

const verifiedModules = new WeakSet();
const MAX_ARTIFACT_BYTES = 16777216;
// The embedding host must obtain the expected digest from its trusted,
// immutable release manifest. Matching an attacker-supplied digest does not
// authenticate an artifact or its source. No downloads occur here.
export async function compileVerifiedLocalActor(bytes, expectedSha256) {
  if (!(bytes instanceof Uint8Array) || bytes.length < 8 || bytes.length > MAX_ARTIFACT_BYTES ||
      typeof expectedSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(expectedSha256)) {
    throw new Error('invalid local actor artifact');
  }
  // Snapshot before awaiting digest: caller mutation must not change the
  // bytes that are subsequently compiled.
  const snapshot = bytes.slice();
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', snapshot));
  const actual = Array.from(hash, byte => byte.toString(16).padStart(2, '0')).join('');
  if (actual !== expectedSha256) throw new Error('local actor digest mismatch');
  const module = await WebAssembly.compile(snapshot);
  verifiedModules.add(module);
  return module;
}

export function createLocalWorkerActor({mount, worker, module, fuel, createStdio,
                                        onNavigation, onNavigationError, onBusy,
                                        onError = () => {}, onStop = () => {}}) {
  if (!(module instanceof WebAssembly.Module) || !verifiedModules.has(module) || !Number.isSafeInteger(fuel) || fuel < 1 ||
      !worker || typeof worker.postMessage !== 'function' || typeof worker.addEventListener !== 'function' ||
      typeof worker.removeEventListener !== 'function' || typeof worker.terminate !== 'function' ||
      typeof createStdio !== 'function' || typeof onError !== 'function' || typeof onStop !== 'function') {
    throw new Error('invalid local Worker actor');
  }
  let host, channel, closed = false, closing = false;
  function cleanup() {
    if (closed) return;
    closed = true;
    worker.removeEventListener('message', message);
    worker.removeEventListener('error', failure);
    worker.removeEventListener('messageerror', failure);
    if (host) host.close();
    if (channel) channel.dispose();
    worker.terminate();
  }
  function fail(error) {
    if (closed) return;
    cleanup();
    onError(error instanceof Error ? error : new Error('local Worker actor failed'));
  }
  function failure() { fail(new Error('local Worker actor transport failed')); }
  function message(event) {
    const data = event.data;
    if (!data || typeof data !== 'object') return fail(new Error('invalid local Worker message'));
    if (data.localRead) return;
    if (data.localOutput) {
      if (!/^output-[1-9][0-9]*$/.test(data.localOutput.id || '')) return fail(new Error('unacknowledged Worker output'));
      return;
    }
    if (data.cap) return fail(new Error('unexpected Worker service operation'));
    if (!Number.isSafeInteger(data.exit) || typeof data.stdout !== 'string' || typeof data.stderr !== 'string') {
      return fail(new Error('invalid local Worker result'));
    }
    if (!closing || data.exit !== 0 || data.wasmTrap !== null || typeof data.report !== 'string' ||
        data.report.length > 65536 || !/^\{:status :ok :result 0(?:\s|})/.test(data.report)) {
      return fail(new Error('local Worker actor terminated unexpectedly'));
    }
    cleanup(); onStop();
  }
  try {
    host = createLocalActorHost({mount, write:text => { if (!closed) channel.write(text); },
      onNavigation, onNavigationError, onBusy, onError:fail});
    channel = createStdio(worker, {onOutput(fd, bytes) {
      if (!closing && !closed) host.output(fd, bytes);
    }});
    if (!channel || typeof channel.write !== 'function' || typeof channel.dispose !== 'function') throw new Error('invalid local stdio channel');
    worker.addEventListener('message', message);
    worker.addEventListener('error', failure);
    worker.addEventListener('messageerror', failure);
    worker.postMessage({module, fuel, localStdio:true, localOutputAck:true, allow:[37,39,41], bind:[]});
  } catch (error) { cleanup(); throw error; }
  return Object.freeze({
    host,
    close() {
      if (!closed && !closing) {
        closing = true;
        try { host.close(); } catch (error) { fail(error); throw error; }
      }
    },
    terminate() { cleanup(); },
  });
}
