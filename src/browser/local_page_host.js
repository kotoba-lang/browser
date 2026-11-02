// Offline adapter for a complete, already-authorized Kotoba page projection.
// The caller owns document provenance and isolation. This adapter does not
// execute scripts, request resources, grant capabilities or implement layout.
const TAGS = new Set(['div', 'span', 'p', 'br', 'section', 'article', 'header', 'footer', 'main',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'b', 'i', 'code']);
const ATTRS = new Set(['id', 'class', 'title']);
const MAX_TEXT = 1048576;

export function commitLocalPageProjection(root, batches) {
  if (!(root instanceof ShadowRoot) || root.mode !== 'closed' || !Array.isArray(batches) || batches.length > 8) {
    throw new Error('invalid page projection');
  }
  const nodes = new Map(), parents = new Set();
  let page = null, operations = 0, textBytes = 0;
  const encoder = new TextEncoder();
  const idOk = id => Number.isSafeInteger(id) && id > 0 && id <= 256;
  const textOk = value => {
    if (typeof value !== 'string' || value.length > MAX_TEXT) throw new Error('invalid page text');
    textBytes += encoder.encode(value).length;
    if (textBytes > MAX_TEXT) throw new Error('page text limit');
    return value;
  };
  for (const batch of batches) {
    if (!Array.isArray(batch) || !batch.length || batch.length > 32) throw new Error('invalid page batch');
    for (const op of batch) {
      if (!Array.isArray(op) || ++operations > 256 || !idOk(op[1])) throw new Error('invalid page operation');
      const [kind, id, value, extra] = op;
      if (kind === ':dom/create-element') {
        if (op.length !== 3 || nodes.has(id) || typeof value !== 'string' || value[0] !== ':' || !TAGS.has(value.slice(1))) throw new Error('invalid page element');
        nodes.set(id, document.createElement(value.slice(1)));
      } else if (kind === ':dom/create-text') {
        if (op.length !== 3 || nodes.has(id)) throw new Error('invalid page text node');
        nodes.set(id, document.createTextNode(textOk(value)));
      } else if (kind === ':dom/set-root') {
        const node = nodes.get(id);
        if (op.length !== 2 || page || !(node instanceof HTMLDivElement) || parents.has(id)) throw new Error('invalid page root');
        page = node;
      } else if (kind === ':dom/set-attr') {
        const node = nodes.get(id);
        if (op.length !== 4 || !(node instanceof Element) || typeof value !== 'string' || value[0] !== ':' || !ATTRS.has(value.slice(1))) throw new Error('invalid page attribute');
        node.setAttribute(value.slice(1), textOk(extra));
      } else if (kind === ':dom/append-child') {
        const parent = nodes.get(id), child = nodes.get(value);
        if (op.length !== 3 || !(parent instanceof Element) || parent instanceof HTMLBRElement || !idOk(value) || !child ||
            parents.has(value) || child === page || child.contains(parent)) throw new Error('invalid page child');
        parent.appendChild(child); parents.add(value);
      } else throw new Error('unknown page operation');
    }
  }
  if (!page || parents.size !== nodes.size - 1 || [...nodes.values()].some(node => node !== page && !page.contains(node))) throw new Error('incomplete page projection');
  root.replaceChildren(page);
}
