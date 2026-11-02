// Offline host for a complete Browser shell projection. The wire uses arrays
// and keyword spellings (for example ":dom/create-element"); conversion
// from the Kotoba document ABI is a separate, not-yet-implemented boundary.
// This module performs no network, persistence or permission operations.
const TAGS = new Set([":main", ":nav", ":button", ":input", ":section", ":pre"]);
const ATTRS = new Set([":aria-label", ":aria-selected", ":data-action", ":data-tab-id", ":data-role", ":value"]);
const ACTIONS = new Set([":tab/new", ":tab/close-active"]);
const MAX_TEXT = 1_048_576;

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
        nodes.set(id, document.createElement(value.slice(1)));
      } else if (kind === ":dom/create-text") {
        if (op.length !== 3 || nodes.has(id)) throw new Error("invalid text node");
        nodes.set(id, document.createTextNode(checkedText(value)));
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
  container.replaceChildren(root);
}
