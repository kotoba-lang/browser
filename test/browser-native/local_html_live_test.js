// Offline test harness: Python owns actor transport and injects a fixture HTML
// response. It is not a broker, a network adapter, or a production app host.
import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from '../../src/browser/local_dom_host.js';
const mount = document.getElementById('mount');
let unbind = null, page = null;
const events = [];
const attach = Element.prototype.attachShadow;
Element.prototype.attachShadow = function(options) { const root = attach.call(this, options); page = root; return root; };
window.htmlLive = {
  present(shell, pageBatches) {
    if (unbind) unbind();
    page = null;
    const projection = commitLocalProjection(mount, shell, pageBatches === null ? undefined : pageBatches);
    unbind = bindLocalInput(mount, projection, message => events.push(encodeLocalEvent(message)));
  },
  take() { return events.shift() ?? null; },
  snapshot() {
    const input = mount.querySelector('input');
    return {url: input?.value ?? null, page: page?.textContent ?? null, tabs: mount.querySelectorAll('nav [data-tab-id]').length};
  },
};
