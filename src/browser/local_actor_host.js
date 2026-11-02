// Local actor transport for an already authenticated Worker/process. This
// module performs no service operations and does not authenticate its source.
// Call output from the acknowledged local stdio callback; write queues stdin.
import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from './local_dom_host.js';

const MAX_FRAME = 1048576;
const MAX_HEADER = 64;
const MAX_INPUT = 65700;

export function createLocalActorHost({ mount, write, onBusy = () => {}, onError = () => {} }) {
  if (!(mount instanceof Element) || typeof write !== 'function' || typeof onBusy !== 'function' || typeof onError !== 'function') {
    throw new Error('invalid local actor host');
  }
  let revision = 0, waiting = true, closed = false, unbind = null;
  let buffer = new Uint8Array(), expected = null;
  const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', { fatal: true });

  function fail(error) {
    if (!closed) {
      closed = true;
      if (unbind) { try { unbind(); } catch (_) { /* active composition requires owner cleanup */ } }
      onError(error);
    }
    throw error;
  }
  function intent(message) {
    if (closed) return;
    if (waiting) { onBusy(); return; }
    try {
      const body = revision + '|' + encodeLocalEvent(message);
      const size = encoder.encode(body).length;
      if (size > MAX_INPUT) throw new Error('local actor input limit');
      waiting = true;
      write(size + '\n' + body);
    } catch (error) { fail(error); }
  }
  function frame(rev, bytes) {
    if (!waiting || rev !== revision + 1 || !Number.isSafeInteger(rev)) throw new Error('unexpected actor revision');
    const batches = JSON.parse(decoder.decode(bytes));
    if (unbind) { unbind(); unbind = null; }
    const projection = commitLocalProjection(mount, batches);
    revision = rev;
    unbind = bindLocalInput(mount, projection, intent);
    waiting = false;
  }
  function parse() {
    while (buffer.length) {
      if (!expected) {
        const nl = buffer.indexOf(10);
        if (nl < 0) { if (buffer.length > MAX_HEADER) throw new Error('actor header limit'); return; }
        if (nl > MAX_HEADER) throw new Error('actor header limit');
        const header = decoder.decode(buffer.subarray(0, nl));
        const match = /^FRAME ([1-9][0-9]{0,15}) ([1-9][0-9]{0,6})$/.exec(header);
        if (!match) throw new Error('invalid actor header');
        const rev = Number(match[1]), length = Number(match[2]);
        if (!Number.isSafeInteger(rev) || length > MAX_FRAME) throw new Error('actor frame limit');
        expected = { rev, length };
        buffer = buffer.slice(nl + 1);
      }
      if (buffer.length < expected.length) return;
      const { rev, length } = expected;
      const bytes = buffer.slice(0, length);
      buffer = buffer.slice(length);
      expected = null;
      frame(rev, bytes);
    }
  }
  return Object.freeze({
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
      if (unbind) unbind();
      closed = true;
      buffer = new Uint8Array();
      write('0\n');
    },
    state() { return { revision, waiting, closed }; },
  });
}
