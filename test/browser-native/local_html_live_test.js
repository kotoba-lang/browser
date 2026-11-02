// Offline test harness: Python owns actor transport and injects a fixture HTML
// response. It is not a broker, a network adapter, or a production app host.
import { createLocalActorHost } from '../../src/browser/local_actor_host.js';
const mount = document.getElementById('mount');
let page = null, notice = null;
const events = [], refusals = [];
const attach = Element.prototype.attachShadow;
Element.prototype.attachShadow = function(options) { const root = attach.call(this, options); page = root; return root; };
const host = createLocalActorHost({mount, write: message => events.push(message), onNavigation: item => { notice = item; },
                                   onNavigationError: item => refusals.push(item)});
window.htmlLive = {
  feed(bytes) {
    page = null;
    host.output(1, Uint8Array.from(bytes));
  },
  take() { return events.shift() ?? null; },
  notice() { return notice; },
  refusals() { return refusals; },
  cancel() { return host.cancelNavigation(notice); },
  async deliver(response) { return host.deliverHtml(notice, response); },
  async deliverFixture(url, body) {
    const response = new Response(body, {headers:{'content-type':'text/html; charset=utf-8'}});
    Object.defineProperty(response, 'url', {value:url});
    return host.deliverHtml(notice, response);
  },
  listItems() { return page ? Array.from(page.querySelectorAll('ul > li'), node => node.textContent) : []; },
  snapshot() {
    const input = mount.querySelector('input');
    return {url: input?.value ?? null, page: page?.textContent ?? null, tabs: mount.querySelectorAll('nav [data-tab-id]').length};
  },
};
