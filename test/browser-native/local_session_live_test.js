// Test-only bridge; the Python harness supplies the local actor transport.
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
import { createLocalSessionStore } from '../../src/browser/local_session_store.js';
import { createLocalSessionPersistence } from '../../src/browser/local_session_persistence.js';
const mount = document.getElementById('mount'), events = [];
let notice = null, page = null, operation = {status:'idle'};
const attach = Element.prototype.attachShadow;
Element.prototype.attachShadow = function(options) { const root = attach.call(this, options); page = root; return root; };
const host = createLocalActorHost({mount, write:value => events.push(value), onNavigation:value => {notice = value;}});
const persistence = createLocalSessionPersistence({host, store:createLocalSessionStore('live-restart')});
function run(action) {
  operation = {status:'pending'};
  action().then(value => {operation = {status:'ok', value};}, error => {operation = {status:'error', message:String(error)};});
}
window.sessionLive = {
  feed(bytes) { host.output(1, Uint8Array.from(bytes)); },
  take() { return events.shift() ?? null; },
  notice() { return notice; },
  state() { return host.state(); },
  operation() { return operation; },
  save() { run(() => persistence.save()); },
  restore() { run(() => persistence.restore()); },
  invalidRestore() { run(() => host.restoreSnapshot('BSS1|bad')); },
  close() { host.close(); },
  async deliverFixture(url) {
    const response = new Response('<!doctype html><html><head><title>Next</title></head><body><section><h1>Next page</h1><p>Second document</p></section></body></html>', {headers:{'content-type':'text/html; charset=utf-8'}});
    Object.defineProperty(response, 'url', {value:url});
    return host.deliverHtml(notice, response);
  },
  snapshot() { return {url:mount.querySelector('input')?.value ?? null,
                        title:mount.querySelector('[data-tab-id]')?.textContent ?? null,
                        tabs:mount.querySelectorAll('nav [data-tab-id]').length,
                        page:page?.textContent ?? null}; },
};
