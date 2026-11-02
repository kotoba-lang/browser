import { encodeLocalHtmlResponse } from '../../src/browser/local_html_response.js';
const result = document.getElementById('result');
const url = 'https://one.example/p?q=x';
const context = { revision: 4, generation: 2, tab: 'tab-1', url };
function response(body, { finalUrl = url, status = 200, contentType = 'text/html; charset=UTF-8', redirected = false, extra = {} } = {}) {
  const value = new Response(body, {status, headers:{'content-type':contentType, ...extra}});
  Object.defineProperties(value, {url:{value:finalUrl}, redirected:{value:redirected}});
  return value;
}
async function reject(value, input = context) {
  let failed = false;
  try { await encodeLocalHtmlResponse(value, input); } catch (_) { failed = true; }
  if (!failed) throw new Error('invalid response accepted');
}
try {
  const framed = await encodeLocalHtmlResponse(response('<p>日本😀</p>'), context);
  const [length, ...rest] = framed.split('\n');
  const payload = rest.join('\n');
  if (Number(length) !== new TextEncoder().encode(payload).length ||
      payload !== `H1|4|2|tab-1|${new TextEncoder().encode(url).length}|${url}<p>日本😀</p>`) throw new Error('incorrect actor frame');
  await reject(response('<p>x</p>', {finalUrl:'https://other.example/'}));
  await reject(response('<p>x</p>', {redirected:true}));
  await reject(response('<p>x</p>', {status:404}));
  await reject(response('<p>x</p>', {contentType:'text/html; charset=shift_jis'}));
  await reject(response('<p>x</p>', {contentType:'text/plain'}));
  await reject(response('<p>x</p>', {extra:{'content-length':'60001'}}));
  await reject(response(Uint8Array.of(0xff)));
  await reject(response('<p>x</p>'), {...context, url:'https://user:secret@one.example/'});
  await reject(response('<p>x</p>'), {...context, tab:'tab-1|bad'});
  await reject(response('<p>x</p>'), {...context, revision:0});
  let cancelled = false;
  const large = new ReadableStream({start(controller) { controller.enqueue(new Uint8Array(60001)); }, cancel() { cancelled = true; }});
  await reject(response(large));
  if (!cancelled) throw new Error('oversized body was not cancelled');
  result.textContent = 'PASS';
} catch (error) { result.textContent = 'FAIL: ' + error.message; }
