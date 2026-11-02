// Local actor transport for an already authenticated Worker/process. This
// module performs no service operations and does not authenticate its source.
// Call output from the acknowledged local stdio callback; write queues stdin.
// onNavigation is an unapproved intent. Only a separately trusted host may
// use it with the canonical broker and a fresh operation-specific decision.
import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from './local_dom_host.js';
import { encodeLocalHtmlResponse } from './local_html_response.js';

const MAX_FRAME = 1048576;
const MAX_HEADER = 64;
const MAX_INPUT = 65700;
const MAX_PENDING_TEXT = 32;
const MAX_PENDING_BYTES = 65536;

export function createLocalActorHost({ mount, write, onNavigation = () => {}, onNavigationError = () => {}, onBusy = () => {}, onError = () => {} }) {
  if (!(mount instanceof Element) || typeof write !== 'function' || typeof onNavigation !== 'function' ||
      typeof onNavigationError !== 'function' || typeof onBusy !== 'function' || typeof onError !== 'function') {
    throw new Error('invalid local actor host');
  }
  let revision = 0, waiting = true, closed = false, unbind = null;
  let projection = null, sent = null, pendingText = [], pendingBytes = 0;
  const pendingNotices = new Map();
  const committedUrls = new Map();
  let pageToken = null;
  const readingResponses = new Map();
  function abortResponses() {
    for (const controller of readingResponses.values()) controller.abort();
  }
  let composing = false, finishingComposition = false, compositionTarget = 0, pendingComposition = [], compositionBytes = 0;
  let buffer = new Uint8Array(), expected = null;
  const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', { fatal: true });
  // Do not let a pointer move the displayed selection while the actor owns
  // an outstanding edit; queued text is based on that selection.
  function holdSelection(event) {
    if (((waiting && sent) || composing || finishingComposition) && event.target instanceof HTMLInputElement) {
      if (event.cancelable) event.preventDefault();
      onBusy();
    }
  }
  mount.addEventListener('pointerdown', holdSelection, true);

  function fail(error) {
    if (!closed) {
      closed = true;
      abortResponses();
      mount.removeEventListener('pointerdown', holdSelection, true);
      pendingText = []; pendingBytes = 0;
      pendingNotices.clear();
      committedUrls.clear(); pageToken = null;
      pendingComposition = []; compositionBytes = 0;
      if (unbind) { unbind(true); unbind = null; }
      onError(error);
    }
    throw error;
  }
  function intent(message) {
    if (closed) return;
    if (message.type === ':ui/composition-start') {
      if (waiting || composing || finishingComposition || pendingText.length) return fail(new Error('composition started while busy'));
      composing = true; compositionTarget = message.target;
    } else if (message.type === ':ui/composition-update' || message.type === ':ui/composition-end') {
      if (!composing || message.target !== compositionTarget) return fail(new Error('unexpected composition event'));
      if (message.type === ':ui/composition-end') { composing = false; finishingComposition = true; }
      if (waiting) {
        try {
          encodeLocalEvent(message);
          const size = encoder.encode(message.text).length;
          if (pendingComposition.length >= MAX_PENDING_TEXT || compositionBytes + size > MAX_PENDING_BYTES) throw new Error('composition queue limit');
          pendingComposition.push(message); compositionBytes += size;
          return;
        } catch (error) { return fail(error); }
      }
    } else if (composing || finishingComposition) { onBusy(); return; }
    if (waiting) {
      // Only consecutive text from the same unchanged selection can be
      // rebased onto the actor's next confirmed caret. Other gestures must
      // wait for a fresh projection; they are explicitly reported busy.
      if (sent && sent.type === ':ui/text' && message.type === ':ui/text' &&
          sent.target === message.target && sent['selection-anchor'] === message['selection-anchor'] &&
          sent['selection-focus'] === message['selection-focus']) {
        try {
          encodeLocalEvent(message);
          const size = encoder.encode(message.text).length;
          if (pendingText.length < MAX_PENDING_TEXT && pendingBytes + size <= MAX_PENDING_BYTES) {
            pendingText.push(message); pendingBytes += size; return;
          }
        } catch (_) { /* report input that cannot be queued */ }
      }
      onBusy(); return;
    }
    send(message);
  }
  function send(message) {
    try {
      const body = revision + '|' + encodeLocalEvent(message);
      const size = encoder.encode(body).length;
      if (size > MAX_INPUT) throw new Error('local actor input limit');
      abortResponses();
      waiting = true;
      sent = message;
      write(size + '\n' + body);
    } catch (error) { fail(error); }
  }
  function pageLink(token, href) {
    if (closed || token !== pageToken) return;
    if (waiting || composing || finishingComposition || pendingText.length) { onBusy(); return; }
    const selected = mount.querySelectorAll('button[data-tab-id][aria-selected="true"]');
    if (selected.length !== 1) return;
    const tab = selected[0].getAttribute('data-tab-id'), base = committedUrls.get(tab);
    if (!base || typeof href !== 'string') return;
    let url;
    try {
      const parsed = new URL(href, base);
      if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.hash || encoder.encode(parsed.href).length > 4096) return;
      url = parsed.href;
    } catch (_) { return; }
    try {
      const body = `L1|${revision}|${tab}|${url}`;
      abortResponses();
      waiting = true; sent = {type: ':host/link'};
      write(encoder.encode(body).length + '\n' + body);
    } catch (error) { fail(error); }
  }
  function frame(rev, bytes, shellLength = null, pageLength = 0, intentLength = 0) {
    if (!waiting || rev !== revision + 1 || !Number.isSafeInteger(rev)) throw new Error('unexpected actor revision');
    const batches = JSON.parse(decoder.decode(shellLength === null ? bytes : bytes.subarray(0, shellLength)));
    const pageBatches = pageLength ? JSON.parse(decoder.decode(bytes.subarray(shellLength, shellLength + pageLength))) : undefined;
    const completed = sent;
    if (completed && completed.type === ':host/response' && (!pageLength || intentLength)) {
      throw new Error('invalid HTML response acknowledgement');
    }
    let notice;
    if (intentLength) {
      if (!completed || ![':ui/submit', ':ui/click', ':host/link'].includes(completed.type)) throw new Error('unexpected navigation intent');
      const item = JSON.parse(decoder.decode(bytes.subarray(shellLength + pageLength)));
      if (!item || Array.isArray(item) || typeof item !== 'object' || Object.keys(item).sort().join(',') !== 'generation,tab,url' ||
          typeof item.tab !== 'string' || !/^tab-[1-9][0-9]{0,9}$/.test(item.tab) ||
          !Number.isSafeInteger(item.generation) || item.generation < 1 || typeof item.url !== 'string' ||
          encoder.encode(item.url).length > 4096) throw new Error('invalid navigation intent');
      notice = Object.freeze({revision: rev, tab: item.tab, generation: item.generation, url: item.url});
    }
    if (completed && (completed.type === ':ui/composition-start' || completed.type === ':ui/composition-update')) {
      // Validate each projection on a detached tree, but keep the actual
      // composing input alive until the browser's compositionend.
      const detached = document.createElement('div');
      const checked = commitLocalProjection(detached, batches, pageBatches);
      const input = detached.querySelector('input[data-role="address"]');
      if (!input || checked.resolve(input)?.id !== completed.target) throw new Error('composition target changed');
      revision = rev; waiting = false; sent = null;
      if (pendingComposition.length) {
        const next = pendingComposition.shift();
        compositionBytes -= encoder.encode(next.text).length;
        send(next);
      }
      return;
    }
    if (completed && completed.type === ':ui/composition-end') { finishingComposition = false; compositionTarget = 0; }
    if (unbind) { unbind(); unbind = null; }
    const nextPageToken = {};
    projection = commitLocalProjection(mount, batches, pageBatches, href => pageLink(nextPageToken, href));
    pageToken = nextPageToken;
    revision = rev;
    unbind = bindLocalInput(mount, projection, intent);
    waiting = false;
    sent = null;
    // Closing a tab invalidates any previously issued host handle for it.
    // The actor still checks the generation for every actual cancellation.
    const tabs = new Set(Array.from(mount.querySelectorAll('button[data-tab-id]'), node => node.getAttribute('data-tab-id')));
    for (const tab of pendingNotices.keys()) if (!tabs.has(tab)) pendingNotices.delete(tab);
    for (const tab of committedUrls.keys()) if (!tabs.has(tab)) committedUrls.delete(tab);
    if (completed && completed.type === ':host/response' && tabs.has(completed.notice.tab)) {
      committedUrls.set(completed.notice.tab, completed.notice.url);
    }
    if (completed && (completed.type === ':host/cancel' || completed.type === ':host/response') &&
        pendingNotices.get(completed.notice.tab) === completed.notice) {
      pendingNotices.delete(completed.notice.tab);
    }
    if (notice) { pendingNotices.set(notice.tab, notice); onNavigation(notice); }
    if (pendingText.length) {
      const input = mount.querySelector('input[data-role="address"]');
      const target = input && projection.resolve(input);
      if (!target || target.id !== pendingText[0].target || document.activeElement !== input ||
          !Number.isInteger(input.selectionStart) || !Number.isInteger(input.selectionEnd)) {
        throw new Error('queued input target changed');
      }
      const next = pendingText.shift();
      pendingBytes -= encoder.encode(next.text).length;
      const backward = input.selectionDirection === 'backward';
      intent({ ...next, 'selection-anchor': backward ? input.selectionEnd : input.selectionStart,
                         'selection-focus': backward ? input.selectionStart : input.selectionEnd });
    }
  }
  function parse() {
    while (buffer.length) {
      if (!expected) {
        const nl = buffer.indexOf(10);
        if (nl < 0) { if (buffer.length > MAX_HEADER) throw new Error('actor header limit'); return; }
        if (nl > MAX_HEADER) throw new Error('actor header limit');
        const header = decoder.decode(buffer.subarray(0, nl));
        if (header === 'REJECT') {
          if (!waiting || !sent || ![':host/response', ':host/cancel'].includes(sent.type)) {
            throw new Error('unexpected actor refusal');
          }
          const refused = sent;
          buffer = buffer.slice(nl + 1);
          sent = null;
          waiting = false;
          // The actor has not advanced its revision or changed state. Keep
          // the displayed page and the notice so the trusted host can cancel
          // or retry; this is a generic refusal, not a diagnostic from HTML.
          onNavigationError(Object.freeze({notice: refused.notice, operation: refused.type === ':host/response' ? 'response' : 'cancel'}));
          continue;
        }
        const match = /^FRAME ([1-9][0-9]{0,15}) ([1-9][0-9]{0,6})$/.exec(header);
        const page = /^PFRAME ([1-9][0-9]{0,15}) ([1-9][0-9]{0,6}) ([1-9][0-9]{0,6})$/.exec(header);
        const nav = /^NFRAME ([1-9][0-9]{0,15}) ([1-9][0-9]{0,6}) (0|[1-9][0-9]{0,6}) ([1-9][0-9]{0,4})$/.exec(header);
        if (!match && !page && !nav) throw new Error('invalid actor header');
        const fields = match || page || nav;
        const rev = Number(fields[1]), shellLength = Number(fields[2]);
        const pageLength = page || nav ? Number(fields[3]) : 0;
        const intentLength = nav ? Number(fields[4]) : 0;
        const length = shellLength + pageLength + intentLength;
        if (!Number.isSafeInteger(rev) || length > MAX_FRAME) throw new Error('actor frame limit');
        expected = { rev, length, shellLength: page || nav ? shellLength : null, pageLength, intentLength };
        buffer = buffer.slice(nl + 1);
      }
      if (buffer.length < expected.length) return;
      const { rev, length, shellLength, pageLength, intentLength } = expected;
      const bytes = buffer.slice(0, length);
      buffer = buffer.slice(length);
      expected = null;
      frame(rev, bytes, shellLength, pageLength, intentLength);
    }
  }
  return Object.freeze({
    // The trusted caller must obtain this Response through the canonical
    // broker after fresh consent and verify its provenance. A Response is not
    // itself proof of approval; neither this API nor the actor authenticates
    // the peer. Do not expose it to an untrusted page or use it as a broker.
    async deliverHtml(notice, response) {
      if (closed) throw new Error('local actor host closed');
      if (!notice || pendingNotices.get(notice.tab) !== notice) throw new Error('unknown navigation intent');
      if (waiting || composing || finishingComposition || pendingText.length) { onBusy(); return false; }
      if (readingResponses.has(notice)) throw new Error('navigation response already reading');
      const expectedRevision = revision;
      const controller = new AbortController();
      readingResponses.set(notice, controller);
      try {
        const payload = await encodeLocalHtmlResponse(response, {revision: expectedRevision, generation: notice.generation,
                                                                 tab: notice.tab, url: notice.url}, {signal:controller.signal});
        if (closed || revision !== expectedRevision || waiting || composing || finishingComposition || pendingText.length ||
            pendingNotices.get(notice.tab) !== notice) throw new Error('navigation response became stale');
        waiting = true;
        sent = {type: ':host/response', notice};
        try { write(payload); } catch (error) { return fail(error); }
        return true;
      } finally { readingResponses.delete(notice); }
    },
    cancelNavigation(notice) {
      if (closed) throw new Error('local actor host closed');
      if (!notice || pendingNotices.get(notice.tab) !== notice) throw new Error('unknown navigation intent');
      if (waiting || composing || finishingComposition || pendingText.length) { onBusy(); return false; }
      try {
        const body = `C1|${revision}|${notice.generation}|${notice.tab}`;
        abortResponses();
        waiting = true;
        sent = {type: ':host/cancel', notice};
        write(encoder.encode(body).length + '\n' + body);
        return true;
      } catch (error) { return fail(error); }
    },
    output(fd, bytes) {
      if (closed) throw new Error('local actor host closed');
      try {
        if (fd !== 1 || !(bytes instanceof Uint8Array)) throw new Error('invalid actor output');
        if (buffer.length + bytes.length > MAX_FRAME + MAX_HEADER + 1) throw new Error('actor buffer limit');
        const next = new Uint8Array(buffer.length + bytes.length);
        next.set(buffer); next.set(bytes, buffer.length); buffer = next;
        parse();
      } catch (error) { fail(error); }
    },
    close() {
      if (closed) return;
      if (unbind) { unbind(true); unbind = null; }
      closed = true;
      abortResponses();
      mount.removeEventListener('pointerdown', holdSelection, true);
      buffer = new Uint8Array(); pendingText = []; pendingBytes = 0;
      pendingNotices.clear();
      committedUrls.clear(); pageToken = null;
      pendingComposition = []; compositionBytes = 0;
      write('0\n');
    },
    state() { return { revision, waiting, closed }; },
  });
}
