// Test-only fake actor frames; actual HTTPS and the consent broker are real.
import { createClientConsentHost, createClientOperationBroker } from './canonical-client-runtime.js';
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
const origin = 'https://service.invalid';
const op = {capability:'test/slow-read', service:'fixture-service', operation:'slow-read', consent:'each-operation',
  'data-categories':['navigation-url'], endpoint:{origin, method:'GET', path:'/slow'}};
const runtime = {schema:'kotoba.app.client-runtime.v1', target:'browser', profile:'trusted-page-v1', entry:'index.html',
  'local-state':{adapter:'indexeddb', schema:'browser.fixture.v1', version:1}, operations:[op]};
const broker = createClientOperationBroker({runtime, requestedCapabilities:[op.capability], approvedOperations:[op],
  hostSupportedOperations:[[op.capability, op.service, op.operation]], consentHost:createClientConsentHost()});
const selector = {capability:op.capability, service:op.service, operation:op.operation};
const mount = document.getElementById('mount'), encoder = new TextEncoder(), writes = [];
let notice = null, state = 'ready', error = '';
const host = createLocalActorHost({mount, write:value => writes.push(value), onNavigation:value => {notice = value;}});
const shell = [[[':dom/create-element',1,':main'], [':dom/set-root',1],
  [':dom/create-element',2,':input'], [':dom/set-attr',2,':data-role','address'],
  [':dom/set-attr',2,':value',origin + '/slow'], [':dom/append-child',1,2],
  [':dom/create-element',3,':section'], [':dom/set-attr',3,':data-role','page'], [':dom/append-child',1,3],
  [':dom/create-element',4,':button'], [':dom/set-attr',4,':data-tab-id','tab-1'], [':dom/append-child',1,4]]];
const body = JSON.stringify(shell), n = encoder.encode(body).length;
host.output(1, encoder.encode(`FRAME 1 ${n}\n${body}`));
mount.querySelector('input').dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}));
const metadata = JSON.stringify({tab:'tab-1', generation:1, url:origin + '/slow'});
host.output(1, encoder.encode(`NFRAME 2 ${n} 0 ${encoder.encode(metadata).length}\n${body}${metadata}`));
window.slowFixture = {
  state() { return {state, error, writes:writes.map(x => x.includes('H1|') ? 'HTML' : x.includes('C1|') ? 'CANCEL' : 'EVENT')}; },
  start() {
    state = 'pending'; error = '';
    broker.invoke(selector, {input:origin + '/slow', init:{method:'GET'}})
      .then(response => { state = 'reading'; return host.deliverHtml(notice, response); })
      .then(() => {state = 'delivered';}, e => {state = 'rejected'; error = String(e.message);});
  },
  cancel() { return host.cancelNavigation(notice); },
  close() { host.close(); },
};
document.getElementById('result').textContent = 'READY';
