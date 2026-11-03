// Offline qualification only: canonical broker is staged from its pinned
// owner by the test runner; no broker, approval, or fetch shim is copied here.
import { createClientConsentHost, createClientOperationBroker } from './canonical-client-runtime.js';
import { encodeLocalHtmlResponse } from './local_html_response.js';
const op = {capability:'test/fixture-read', service:'fixture-service', operation:'read', consent:'each-operation',
  'data-categories':['navigation-url'], endpoint:{origin:'https://service.invalid', method:'GET', path:'/page'}};
const nextOp = {capability:'test/fixture-next', service:'fixture-service', operation:'read-next', consent:'each-operation',
  'data-categories':['navigation-url'], endpoint:{origin:'https://service.invalid', method:'GET', path:'/next'}};
const runtime = {schema:'kotoba.app.client-runtime.v1', target:'browser', profile:'trusted-page-v1', entry:'index.html',
  'local-state':{adapter:'indexeddb', schema:'browser.fixture.v1', version:1}, operations:[op, nextOp]};
const consentHost = createClientConsentHost();
const broker = createClientOperationBroker({runtime, requestedCapabilities:[op.capability, nextOp.capability], approvedOperations:[op, nextOp],
  hostSupportedOperations:[[op.capability, op.service, op.operation], [nextOp.capability, nextOp.service, nextOp.operation]], consentHost});
const selector = {capability:op.capability, service:op.service, operation:op.operation};
const nextSelector = {capability:nextOp.capability, service:nextOp.service, operation:nextOp.operation};
let state = {status:'ready', message:'', encoded:''};
let savedResponse = null;
window.brokerFixture = {
  state() { const dialog = document.querySelector('dialog.kotoba-client-consent'); return {...state, open:dialog.open,
    scope:dialog.querySelector('.kotoba-client-consent-scope').textContent}; },
  start(path, context = {revision:4, generation:1, tab:'tab-1'}) {
    state = {status:'pending', message:'', encoded:''};
    savedResponse = null;
    const url = 'https://service.invalid' + path;
    broker.invoke(path === '/next' ? nextSelector : selector, {input:url, init:{method:'GET'}})
      .then(response => { savedResponse = response.clone(); return encodeLocalHtmlResponse(response, {...context, url}); })
      .then(encoded => { state = {status:'ok', message:'', encoded}; })
      .catch(error => { state = {status:'error', message:String(error.message), encoded:''}; });
  },
  takeResponse() { const response = savedResponse; savedResponse = null; return response; },
};
document.getElementById('result').textContent = 'READY';
