// Offline host for a complete Browser shell projection. The wire uses arrays
// and keyword spellings (for example ":dom/create-element"); conversion
// from the Wasm document ABI remains a separate boundary.
// This module performs no network, persistence or permission operations.
const TAGS = new Set([":main", ":nav", ":button", ":input", ":section", ":pre"]);
const ATTRS = new Set([":aria-label", ":aria-selected", ":data-action", ":data-tab-id", ":data-role", ":value",
                       ":data-selection-start", ":data-selection-end", ":data-caret"]);
const ACTIONS = new Set([":tab/new", ":tab/close-active"]);
const MAX_TEXT = 1_048_576;
const composing = new WeakSet();
const boundContainers = new WeakSet();

function idValue(value) {
  return Number.isSafeInteger(value) && value > 0 && value <= 256;
}

function checkedText(value) {
  if (typeof value !== "string" || value.length > MAX_TEXT) throw new Error("invalid text");
  return value;
}

// Build an entirely detached tree, validate every operation and only then
// replace the live root. The caller must still authenticate the originating
// guest and current state; valid syntax alone grants no authority.
export function commitLocalProjection(container, batches) {
  if (!(container instanceof Element) || !Array.isArray(batches) || batches.length > 8) {
    throw new Error("invalid projection");
  }
  const nodes = new Map();
  const nodeIds = new WeakMap();
  const selections = new Map();
  const parents = new Set();
  let root = null;
  let count = 0;
  for (const batch of batches) {
    if (!Array.isArray(batch) || batch.length === 0 || batch.length > 32) throw new Error("invalid batch");
    for (const op of batch) {
      if (!Array.isArray(op) || ++count > 256 || !idValue(op[1])) throw new Error("invalid operation");
      const [kind, id, value, extra] = op;
      if (kind === ":dom/create-element") {
        if (op.length !== 3 || nodes.has(id) || !TAGS.has(value)) throw new Error("invalid element");
        const node = document.createElement(value.slice(1));
        nodes.set(id, node);
        nodeIds.set(node, id);
      } else if (kind === ":dom/create-text") {
        if (op.length !== 3 || nodes.has(id)) throw new Error("invalid text node");
        const node = document.createTextNode(checkedText(value));
        nodes.set(id, node);
        nodeIds.set(node, id);
      } else if (kind === ":dom/set-root") {
        const node = nodes.get(id);
        if (op.length !== 2 || root || !(node instanceof Element) || parents.has(id)) throw new Error("invalid root");
        root = node;
      } else if (kind === ":dom/set-attr") {
        const node = nodes.get(id);
        if (op.length !== 4 || !(node instanceof Element) || !ATTRS.has(value)) throw new Error("invalid attribute");
        if (value === ":aria-selected") {
          if (typeof extra !== "boolean") throw new Error("invalid selected state");
          node.setAttribute("aria-selected", String(extra));
        } else if (value === ":data-action") {
          if (!ACTIONS.has(extra)) throw new Error("invalid action");
          node.setAttribute("data-action", extra);
        } else if ([":data-selection-start", ":data-selection-end", ":data-caret"].includes(value)) {
          if (!(node instanceof HTMLInputElement) || !Number.isSafeInteger(extra) || extra < 0 || extra > MAX_TEXT) throw new Error("invalid selection");
          const selection = selections.get(node) || {};
          selection[value] = extra;
          selections.set(node, selection);
        } else {
          const text = checkedText(extra);
          const name = value.slice(1);
          node.setAttribute(name, text);
          if (name === "value") {
            if (!(node instanceof HTMLInputElement)) throw new Error("invalid value target");
            node.value = text;
          }
        }
      } else if (kind === ":dom/append-child") {
        const parent = nodes.get(id);
        const child = nodes.get(value);
        if (op.length !== 3 || !(parent instanceof Element) || !idValue(value) || !child ||
            parents.has(value) || child === root || child.contains(parent) || parent instanceof HTMLInputElement) {
          throw new Error("invalid child");
        }
        parent.appendChild(child);
        parents.add(value);
      } else {
        throw new Error("unknown operation");
      }
    }
  }
  if (!root || parents.size !== nodes.size - 1 || [...nodes.values()].some(node => node !== root && !root.contains(node))) {
    throw new Error("incomplete projection");
  }
  for (const [node, selection] of selections) {
    const start = selection[":data-selection-start"], end = selection[":data-selection-end"], caret = selection[":data-caret"];
    const midPair = p => p > 0 && p < node.value.length &&
      node.value.charCodeAt(p - 1) >= 0xd800 && node.value.charCodeAt(p - 1) <= 0xdbff &&
      node.value.charCodeAt(p) >= 0xdc00 && node.value.charCodeAt(p) <= 0xdfff;
    if (!Number.isInteger(start) || !Number.isInteger(end) || !Number.isInteger(caret) ||
        start > end || end > node.value.length || (caret !== start && caret !== end) ||
        midPair(start) || midPair(end) || midPair(caret)) throw new Error("invalid selection bounds");
  }
  const active = document.activeElement;
  const keepFocus = active instanceof HTMLInputElement && container.contains(active) && active.getAttribute("data-role") === "address";
  if (keepFocus && composing.has(active)) throw new Error("composition in progress");
  const address = root.querySelector('input[data-role="address"]');
  if (keepFocus && (!address || !selections.has(address))) throw new Error("missing selection");
  container.replaceChildren(root);
  if (keepFocus) {
    const selection = selections.get(address);
    const start = selection[":data-selection-start"], end = selection[":data-selection-end"], caret = selection[":data-caret"];
    address.focus({ preventScroll: true });
    address.setSelectionRange(start, end, caret === start && start !== end ? "backward" : "forward");
  }
  // The handle becomes stale as soon as another projection replaces this
  // root. It does not prove a guest's identity or approve any operation.
  return Object.freeze({
    resolve(target) {
      if (root.parentNode !== container || container.childNodes.length !== 1 || !(target instanceof Node) || !root.contains(target)) return null;
      let node = target;
      while (node && node !== root && !(node instanceof HTMLButtonElement) && !(node instanceof HTMLInputElement)) node = node.parentNode;
      const id = nodeIds.get(node);
      return id ? { id, node } : null;
    },
  });
}

// Deliver local UI intent only. The receiver must serialize events, bind them
// to its trusted snapshot and reject stale events through the Kotoba bridge.
// Unsupported cancelable edits are prevented to avoid diverging from the
// model. Browser-native composition can remain non-cancelable and requires an
// IME-aware host loop.
export function bindLocalInput(container, projection, deliver) {
  if (!(container instanceof Element) || typeof projection?.resolve !== "function" || typeof deliver !== "function") throw new Error("invalid input binding");
  if (boundContainers.has(container)) throw new Error("input already bound");
  boundContainers.add(container);
  const activeComposition = new Set();
  function route(event) {
    if (event.defaultPrevented) return;
    const resolved = projection.resolve(event.target);
    if (!resolved) return;
    const { id, node } = resolved;
    if (event.type === "click" && node instanceof HTMLButtonElement) {
      deliver({ type: ":ui/click", target: id });
      return;
    }
    if (!(node instanceof HTMLInputElement) || node.getAttribute("data-role") !== "address") return;
    let message = null;
    let cancel = false;
    if (event.type === "beforeinput" && !event.isComposing) {
      if (event.inputType === "insertText" && typeof event.data === "string") message = { type: ":ui/text", target: id, text: event.data };
      if (event.inputType === "deleteContentBackward") message = { type: ":ui/backspace", target: id };
      if (event.inputType === "deleteContentForward") message = { type: ":ui/delete", target: id };
      cancel = true;
    } else if (event.type === "paste" && event.clipboardData) {
      message = { type: ":ui/text", target: id, text: event.clipboardData.getData("text/plain") };
      cancel = true;
    } else if (event.type === "keydown" && !event.isComposing) {
      if (event.key === "Enter") message = { type: ":ui/submit", target: id };
      else if (event.key === "ArrowLeft" || event.key === "ArrowRight") message = { type: ":ui/move", target: id, delta: event.key === "ArrowLeft" ? -1 : 1, extend: event.shiftKey };
      else if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "a") message = { type: ":ui/select-all", target: id };
      cancel = message !== null;
    } else if (event.type === "compositionstart") {
      composing.add(node);
      activeComposition.add(node);
      message = { type: ":ui/composition-start", target: id };
    }
    else if (event.type === "compositionupdate" || event.type === "compositionend") {
      if (event.type === "compositionend") {
        composing.delete(node);
        activeComposition.delete(node);
      }
      message = { type: event.type === "compositionupdate" ? ":ui/composition-update" : ":ui/composition-end", target: id, text: event.data || "" };
    }
    if (cancel && event.cancelable) event.preventDefault();
    if (message) {
      if ([":ui/text", ":ui/backspace", ":ui/delete", ":ui/move", ":ui/composition-start"].includes(message.type) &&
          Number.isInteger(node.selectionStart) && Number.isInteger(node.selectionEnd)) {
        const backward = node.selectionDirection === "backward";
        message["selection-anchor"] = backward ? node.selectionEnd : node.selectionStart;
        message["selection-focus"] = backward ? node.selectionStart : node.selectionEnd;
      }
      deliver(message);
    }
  }
  const types = ["click", "beforeinput", "paste", "keydown", "compositionstart", "compositionupdate", "compositionend"];
  for (const type of types) container.addEventListener(type, route);
  return () => {
    if (activeComposition.size) throw new Error("composition in progress");
    for (const type of types) container.removeEventListener(type, route);
    boundContainers.delete(container);
  };
}
