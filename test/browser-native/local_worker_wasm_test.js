import { createLocalWorkerActor, compileVerifiedLocalActor } from '../../src/browser/local_worker_actor.js';
const result = document.getElementById('result');
try {
  const bytes = Uint8Array.from(atob(window.WASM_PROBE), c => c.charCodeAt(0));
  const module = await compileVerifiedLocalActor(bytes, window.WASM_DIGEST), worker = new Worker('./amu-static-host.js');
  let stopped = false, error = null;
  const actor = createLocalWorkerActor({mount:document.getElementById('mount'),worker,module,fuel:1000000,
    createStdio:createKotobaLocalStdio,onError:e => {error = e;},onStop:() => {stopped = true;}});
  const wait = async test => {
    const end = performance.now() + 20000;
    while (!test()) { if (error) throw error; if (performance.now() > end) throw new Error('timeout'); await new Promise(r => setTimeout(r, 10)); }
  };
  await wait(() => actor.host.state().revision === 1);
  const snapshot = await Promise.race([actor.host.requestSnapshot(), new Promise((_, reject) => setTimeout(() => reject(new Error('snapshot timeout')), 20000))]);
  if (snapshot.revision !== 1 || snapshot.wire !== 'BSS1|x') throw new Error('worker snapshot mismatch');
  actor.close(); await wait(() => stopped);
  if (error || !actor.host.state().closed) throw new Error('worker did not close');
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
