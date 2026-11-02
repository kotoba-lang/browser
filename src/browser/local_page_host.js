// Offline adapter for a complete, already-authorized Kotoba page projection.
// The caller owns document provenance and isolation. This adapter does not
// execute scripts, request resources, grant capabilities or implement layout.
const TAGS = new Set(['div', 'span', 'p', 'br', 'section', 'article', 'header', 'footer', 'main',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'b', 'i', 'code', 'ul', 'ol', 'li', 'a',
  'hr', 'blockquote', 'small', 's', 'mark', 'sub', 'sup', 'q']);
const ATTRS = new Set(['id', 'class', 'title']);
const MAX_TEXT = 1048576;
const pageBindings = new WeakMap();

export function commitLocalPageProjection(root, batches, onLink = () => {}) {
  if (!(root instanceof ShadowRoot) || root.mode !== 'closed' || !Array.isArray(batches) || batches.length > 8 || typeof onLink !== 'function') {
    throw new Error('invalid page projection');
  }
  const nodes = new Map(), parents = new Set(), links = new WeakMap();
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
      } else if (kind === ':dom/create-comment') {
        if (op.length !== 3 || nodes.has(id)) throw new Error('invalid page comment node');
        nodes.set(id, document.createComment(textOk(value)));
      } else if (kind === ':dom/set-root') {
        const node = nodes.get(id);
        if (op.length !== 2 || page || !(node instanceof HTMLDivElement) || parents.has(id)) throw new Error('invalid page root');
        page = node;
      } else if (kind === ':dom/set-attr') {
        const node = nodes.get(id);
        if (op.length !== 4 || !(node instanceof Element) || typeof value !== 'string' || value[0] !== ':') throw new Error('invalid page attribute');
        if (value === ':href') {
          if (!(node instanceof HTMLAnchorElement) || links.has(node)) throw new Error('invalid page link');
          links.set(node, textOk(extra));
          node.setAttribute('role', 'link'); node.tabIndex = 0;
        } else {
          if (!ATTRS.has(value.slice(1))) throw new Error('invalid page attribute');
          node.setAttribute(value.slice(1), textOk(extra));
        }
      } else if (kind === ':dom/append-child') {
        const parent = nodes.get(id), child = nodes.get(value);
        if (op.length !== 3 || !(parent instanceof Element) || (parent instanceof HTMLBRElement || parent instanceof HTMLHRElement) || !idOk(value) || !child ||
            parents.has(value) || child === page || child.contains(parent)) throw new Error('invalid page child');
        parent.appendChild(child); parents.add(value);
      } else throw new Error('unknown page operation');
    }
  }
  if (!page || parents.size !== nodes.size - 1 || [...nodes.values()].some(node => node !== page && !page.contains(node))) throw new Error('incomplete page projection');
  const activate = event => {
    const anchor = event.composedPath().find(node => links.has(node));
    if (!anchor) return;
    if (event.type === 'keydown' && event.key !== 'Enter') return;
    // No live href is installed: default browser navigation must never
    // bypass the operation broker, even for synthetic or modified events.
    if (event.cancelable) event.preventDefault();
    if (!event.isTrusted || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey ||
        (event.type === 'click' && event.button !== 0)) return;
    if (event.type === 'keydown' && event.repeat) return;
    onLink(links.get(anchor));
  };
  const previous = pageBindings.get(root);
  if (previous) { root.removeEventListener('click', previous, true); root.removeEventListener('keydown', previous, true); }
  root.addEventListener('click', activate, true);
  root.addEventListener('keydown', activate, true);
  pageBindings.set(root, activate);
  root.replaceChildren(page);
}
