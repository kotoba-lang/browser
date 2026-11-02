// Local actor transport for an already authenticated Worker/process. This
// module performs no service operations and does not authenticate its source.
// Call output from the acknowledged local stdio callback; write queues stdin.
import { bindLocalInput, commitLocalProjection, encodeLocalEvent } from './local_dom_host.js';

const MAX_FRAME = 1048576;
const MAX_HEADER = 64;
const MAX_INPUT = 65700;
const MAX_PENDING_TEXT = 32;
const MAX_PENDING_BYTES = 65536;

export function createLocalActorHost({ mount, write, onBusy = () => {}, onError = () => {} }) {
  if (!(mount instanceof Element) || typeof write !== 'function' || typeof onBusy !== 'function' || typeof onError !== 'function') {
    throw new Error('invalid local actor host');
  }
  let revision = 0, waiting = true, closed = false, unbind = null;
  let projection = null, sent = null, pendingText = [], pendingBytes = 0;
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
      mount.removeEventListener('pointerdown', holdSelection, true);
      pendingText = []; pendingBytes = 0;
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
      waiting = true;
      sent = message;
      write(size + '\n' + body);
    } catch (error) { fail(error); }
  }
  function frame(rev, bytes) {
    if (!waiting || rev !== revision + 1 || !Number.isSafeInteger(rev)) throw new Error('unexpected actor revision');
    const batches = JSON.parse(decoder.decode(bytes));
    const completed = sent;
    if (completed && (completed.type === ':ui/composition-start' || completed.type === ':ui/composition-update')) {
      // Validate each projection on a detached tree, but keep the actual
      // composing input alive until the browser's compositionend.
      const detached = document.createElement('div');
      const checked = commitLocalProjection(detached, batches);
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
    projection = commitLocalProjection(mount, batches);
    revision = rev;
    unbind = bindLocalInput(mount, projection, intent);
    waiting = false;
    sent = null;
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
      if (unbind) { unbind(true); unbind = null; }
      closed = true;
      mount.removeEventListener('pointerdown', holdSelection, true);
      buffer = new Uint8Array(); pendingText = []; pendingBytes = 0;
      pendingComposition = []; compositionBytes = 0;
      write('0\n');
    },
    state() { return { revision, waiting, closed }; },
  });
}
