#!/usr/bin/env python3
"""Exercise the native actor and IndexedDB across separate Chrome/actor runs."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec); spec.loader.exec_module(live)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True)
    parser.add_argument('--actor-command-file', required=True, type=Path)
    parser.add_argument('--actor-cwd', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    command = json.loads(args.actor_command_file.read_text())
    assert isinstance(command, list) and command and all(isinstance(x, str) for x in command)
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    requests = []
    with tempfile.TemporaryDirectory(prefix='session-live-', dir=out) as profile:
        def launch():
            actor = live.Actor(command, args.actor_cwd)
            browser = None
            try:
                browser = live.Pipe(args.browser, profile)
                version = browser.call('Browser.getVersion')['product']
                target = browser.call('Target.createTarget', {'url':'about:blank'})['targetId']
                session = browser.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
                browser.call('Fetch.enable', {'patterns':[{'urlPattern':'http*','requestStage':'Request'}]}, session)
                def evaluate(expr, promise=False):
                    value = browser.call('Runtime.evaluate', {'expression':expr, 'awaitPromise':promise, 'returnByValue':True}, session)
                    while browser.events:
                        event = browser.events.pop(0)
                        if event.get('method') == 'Fetch.requestPaused':
                            requests.append(event['params']['request']['url'])
                            browser.send('Fetch.failRequest', {'requestId':event['params']['requestId'], 'errorReason':'BlockedByClient'}, event.get('sessionId'))
                    if 'exceptionDetails' in value: raise RuntimeError(value['exceptionDetails'])
                    return value['result'].get('value')
                browser.call('Page.navigate', {'url':(ROOT / 'test/browser-native/local_session_live.html').as_uri()}, session)
                deadline = time.monotonic() + 30
                while not evaluate('typeof window.sessionLive === "object"'):
                    if time.monotonic() > deadline: raise TimeoutError('fixture load')
                    time.sleep(.05)
                def frame(expected):
                    deadline = time.monotonic() + 180
                    while True:
                        header = actor.line(deadline)
                        if header == b'REJECT' and expected != 'REJECT': raise RuntimeError(('unexpected actor refusal', expected))
                        if header == b'REJECT' and expected == 'REJECT':
                            evaluate('window.sessionLive.feed(' + json.dumps(list(b'REJECT\n')) + ')')
                            return header
                        if header.startswith((b'FRAME ', b'PFRAME ', b'NFRAME ', b'SFRAME ')): break
                        if header.startswith((b'KEXE_TRAP', b'seed:', b'ACTOR_EXIT')): raise RuntimeError(('actor', header))
                    fields = header.split()
                    assert fields[0].decode() == expected, header
                    if fields[0] == b'NFRAME': length = sum(map(int, fields[2:5]))
                    elif fields[0] == b'PFRAME': length = sum(map(int, fields[2:4]))
                    else: length = int(fields[2])
                    assert 0 < length <= 1048576
                    while len(actor.buf) < length: actor.more(deadline)
                    data, actor.buf = actor.buf[:length], actor.buf[length:]
                    evaluate('window.sessionLive.feed(' + json.dumps(list(header + b'\n' + data)) + ')')
                    return header
                def take():
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        result = evaluate('window.sessionLive.take()')
                        if result is not None: return result
                        time.sleep(.05)
                    raise TimeoutError(('actor input', evaluate('window.sessionLive.operation()')))
                def exchange(expected):
                    value = take().encode('utf-8')
                    actor.proc.stdin.write(value); actor.proc.stdin.flush()
                    return frame(expected)
                def wait():
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        status = evaluate('window.sessionLive.operation()')
                        if status['status'] != 'pending': return status
                        time.sleep(.05)
                    raise TimeoutError('operation')
                def click(selector):
                    rect = evaluate('(()=>{const r=document.querySelector(' + json.dumps(selector) + ').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()')
                    for kind in ('mousePressed', 'mouseReleased'):
                        browser.call('Input.dispatchMouseEvent', {'type':kind, 'x':rect['x'], 'y':rect['y'], 'button':'left', 'clickCount':1}, session)
                frame('FRAME')
                return actor, browser, session, version, evaluate, exchange, wait, click
            except Exception:
                if browser: browser.close()
                actor.close()
                raise
        def stop(actor, browser, evaluate):
            evaluate('window.sessionLive.close()')
            data = evaluate('window.sessionLive.take()')
            assert data == '0\n', repr(data)
            actor.proc.stdin.write(data.encode()); actor.proc.stdin.flush()
            deadline = time.monotonic() + 30
            while True:
                line = actor.line(deadline)
                if line.startswith(b'ACTOR_EXIT='):
                    assert line == b'ACTOR_EXIT=0', line
                    break
            browser.close(); actor.close()
        actor, browser, session, version, evaluate, exchange, wait, click = launch()
        try:
            click('button[data-action=":tab/new"]'); exchange('FRAME')
            click('input'); url = 'https://one.example/'
            browser.call('Input.insertText', {'text':url}, session); exchange('FRAME')
            browser.call('Input.dispatchKeyEvent', {'type':'keyDown', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            browser.call('Input.dispatchKeyEvent', {'type':'keyUp', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            exchange('NFRAME')
            assert evaluate('window.sessionLive.deliverFixture(' + json.dumps(url) + ')', True)
            exchange('PFRAME')
            before = evaluate('window.sessionLive.snapshot()')
            assert before == {'url':url, 'title':'Next', 'tabs':1, 'page':'Next pageSecond document'}, before
            evaluate('window.sessionLive.save()'); exchange('SFRAME')
            saved = wait()
            assert saved['status'] == 'ok' and saved['value'] == {'revision':5,'storeRevision':1,'stale':False}, saved
            stop(actor, browser, evaluate)
        finally:
            browser.close(); actor.close()
        actor, browser, session, version2, evaluate, exchange, wait, click = launch()
        try:
            evaluate('window.sessionLive.invalidRestore()'); exchange('REJECT')
            rejected = wait()
            assert rejected['status'] == 'error' and evaluate('window.sessionLive.state().revision') == 1, rejected
            evaluate('window.sessionLive.restore()'); exchange('FRAME')
            restored = wait()
            assert restored == {'status':'ok','value':True}, restored
            after = evaluate('window.sessionLive.snapshot()')
            assert after == {'url':url, 'title':'Next', 'tabs':1, 'page':None}, after
            assert evaluate('window.sessionLive.notice()') is None
            assert evaluate('window.sessionLive.state().revision') == 2
            assert evaluate('window.sessionLive.take()') is None
            stop(actor, browser, evaluate)
        finally:
            browser.close(); actor.close()
    assert version == version2 and not requests, requests
    names = ['scripts/check-local-session-live.py','scripts/check-local-native-live.py',
             'test/browser-native/local_session_live.html','test/browser-native/local_session_live_test.js',
             'src/browser/local_session_store.js','src/browser/local_session_persistence.js',
             'src/browser/local_actor_host.js','src/browser/local_dom_host.js','src/browser/local_page_host.js',
             'src/browser/local_html_response.js']
    receipt = {'status':'PASS_LOCAL_SESSION_LIVE_RESTART','browser':version,'before':before,'after':after,
               'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names},
               'limitations':['test-only actor transport and injected HTML response', 'no actor-channel authentication',
                              'checksum is not authentication; no rollback or crash recovery qualification',
                              'cached page content and site storage are not restored']}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version)

if __name__ == '__main__': main()
