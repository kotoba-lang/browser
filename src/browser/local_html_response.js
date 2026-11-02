// Eligibility/encoding only. The trusted caller must first obtain the response
// through the canonical operation broker and verify its identity; this module
// does not fetch, authenticate a Response, or grant/remember any approval.
const MAX_BODY_BYTES = 60000;
const MAX_URL_BYTES = 4096;
const MAX_ACTOR_INPUT = 65700;
const encoder = new TextEncoder();

export async function encodeLocalHtmlResponse(response, { revision, generation, tab, url }) {
  if (!(response instanceof Response) || !Number.isSafeInteger(revision) || revision < 1 ||
      !Number.isSafeInteger(generation) || generation < 1 || typeof tab !== 'string' ||
      !/^tab-[1-9][0-9]{0,9}$/.test(tab) || typeof url !== 'string') throw new Error('invalid HTML response context');
  let parsed;
  try { parsed = new URL(url); } catch (_) { throw new Error('invalid HTML response URL'); }
  const urlBytes = encoder.encode(url).length;
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.hash || parsed.href !== url ||
      urlBytes > MAX_URL_BYTES || response.url !== url || response.redirected || response.type === 'opaque' ||
      response.status < 200 || response.status >= 300) throw new Error('HTML response endpoint or status rejected');
  const mime = response.headers.get('content-type') || '';
  if (!/^text\/html(?:\s*;\s*charset\s*=\s*(?:utf-8|"utf-8"))?\s*$/i.test(mime)) throw new Error('HTML response MIME rejected');
  const length = response.headers.get('content-length');
  if (length !== null && (!/^(0|[1-9][0-9]*)$/.test(length) || !Number.isSafeInteger(Number(length)) || Number(length) > MAX_BODY_BYTES)) {
    throw new Error('HTML response length rejected');
  }
  if (!response.body) throw new Error('HTML response body missing');
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0, timer;
  const deadline = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('HTML response timeout')), 30000); });
  try {
    while (true) {
      const { value, done } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      if (!(value instanceof Uint8Array)) throw new Error('invalid HTML response chunk');
      total += value.length;
      if (total > MAX_BODY_BYTES) throw new Error('HTML response body limit');
      chunks.push(value);
    }
  } catch (error) {
    reader.cancel().catch(() => {});
    throw error;
  } finally { clearTimeout(timer); reader.releaseLock(); }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let html;
  try { html = new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
  catch (_) { throw new Error('invalid UTF-8 HTML response'); }
  const payload = `H1|${revision}|${generation}|${tab}|${urlBytes}|${url}${html}`;
  const size = encoder.encode(payload).length;
  if (size > MAX_ACTOR_INPUT) throw new Error('HTML actor input limit');
  return `${size}\n${payload}`;
}
