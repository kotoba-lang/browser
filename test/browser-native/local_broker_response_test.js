// Offline qualification only: canonical broker is staged from its pinned
// owner by the test runner; no broker, approval, or fetch shim is copied here.
import { createClientConsentHost, createClientOperationBroker } from './canonical-client-runtime.js';
import { encodeLocalHtmlResponse } from './local_html_response.js';
const op = {capability:'test/fixture-read', service:'fixture-service', operation:'read', consent:'each-operation',
  'data-categories':['navigation-url'], endpoint:{origin:'https://service.invalid', method:'GET', path:'/page'}};
const runtime = {schema:'kotoba.app.client-runtime.v1', target:'browser', profile:'trusted-page-v1', entry:'index.html',
  'local-state':{adapter:'indexeddb', schema:'browser.fixture.v1', version:1}, operations:[op]};
const consentHost = createClientConsentHost();
const broker = createClientOperationBroker({runtime, requestedCapabilities:[op.capability], approvedOperations:[op],
  hostSupportedOperations:[[op.capability, op.service, op.operation]], consentHost});
const selector = {capability:op.capability, service:op.service, operation:op.operation};
let state = {status:'ready', message:'', encoded:''};
window.brokerFixture = {
  state() { const dialog = document.querySelector('dialog.kotoba-client-consent'); return {...state, open:dialog.open,
    scope:dialog.querySelector('.kotoba-client-consent-scope').textContent}; },
  start(path, context = {revision:4, generation:1, tab:'tab-1'}) {
    state = {status:'pending', message:'', encoded:''};
    const url = 'https://service.invalid' + path;
    broker.invoke(selector, {input:url, init:{method:'GET'}})
      .then(response => encodeLocalHtmlResponse(response, {...context, url}))
      .then(encoded => { state = {status:'ok', message:'', encoded}; })
      .catch(error => { state = {status:'error', message:String(error.message), encoded:''}; });
  },
};
document.getElementById('result').textContent = 'READY';
