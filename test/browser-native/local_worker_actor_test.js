import { createLocalWorkerActor } from '../../src/browser/local_worker_actor.js';
const result = document.getElementById('result'), encoder = new TextEncoder();
try {
  class FakeWorker extends EventTarget {
    constructor() { super(); this.sent = []; this.terminated = false; }
    postMessage(data) { this.sent.push(data); }
    terminate() { this.terminated = true; }
    send(data) { this.dispatchEvent(new MessageEvent('message', {data})); }
  }
  const worker = new FakeWorker(), errors = [];
  const module = await WebAssembly.compile(Uint8Array.from([0,97,115,109,1,0,0,0]));
  let stopped = false;
  const actor = createLocalWorkerActor({mount:document.getElementById('mount'), worker, module, fuel:1000,
    createStdio:createKotobaLocalStdio, onError:error => errors.push(error), onStop:() => {stopped = true;}});
  const shell = [[[':dom/create-element',1,':main'],[':dom/set-root',1],
    [':dom/create-element',2,':input'],[':dom/set-attr',2,':data-role','address'],
    [':dom/set-attr',2,':value',''],[':dom/set-attr',2,':data-selection-start',0],
    [':dom/set-attr',2,':data-selection-end',0],[':dom/set-attr',2,':data-caret',0],
    [':dom/append-child',1,2],[':dom/create-element',3,':section'],
    [':dom/set-attr',3,':data-role','page'],[':dom/append-child',1,3]]];
  const body = JSON.stringify(shell);
  worker.send({localOutput:{id:'output-1',fd:1,bytes:encoder.encode(`FRAME 1 ${encoder.encode(body).length}\n${body}`)}});
  await new Promise(resolve => setTimeout(resolve, 0));
  if (!worker.sent.some(x => x.localOutputReply?.id === 'output-1' && x.localOutputReply.ok)) throw new Error('frame not acknowledged');
  const snapshot = actor.host.requestSnapshot();
  worker.send({localRead:{id:'local-1',request:'128'}});
  if (!worker.sent.some(x => x.localReadReply?.id === 'local-1' && x.localReadReply.content === '4\nS1|1')) throw new Error('request not queued');
  worker.send({localOutput:{id:'output-2',fd:1,bytes:encoder.encode('SFRAME 1 6\nBSS1|x')}});
  if ((await snapshot).wire !== 'BSS1|x') throw new Error('snapshot mismatch');
  await new Promise(resolve => setTimeout(resolve, 0));
  if (!worker.sent.some(x => x.localOutputReply?.id === 'output-2' && x.localOutputReply.ok)) throw new Error('snapshot not acknowledged');
  actor.close(); worker.send({localRead:{id:'local-2',request:'128'}});
  if (!worker.sent.some(x => x.localReadReply?.id === 'local-2' && x.localReadReply.content === '0\n')) throw new Error('close not queued');
  worker.send({localOutput:{id:'output-3',fd:1,bytes:encoder.encode('{:status :ok :result 0}\n')}});
  await new Promise(resolve => setTimeout(resolve, 0));
  if (!worker.sent.some(x => x.localOutputReply?.id === 'output-3' && x.localOutputReply.ok)) throw new Error('report not acknowledged');
  worker.send({exit:0,stdout:'',stderr:'',wasmTrap:null,report:'{:status :ok :result 0 :fuel {}}'});
  if (!stopped || !worker.terminated || errors.length) throw new Error('completion mismatch');
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
