#!/usr/bin/env python3
"""Offline Chrome UI -> native HTML actor -> Chrome page integration test."""
import argparse
import base64
from contextlib import ExitStack
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def run(args):
    command = json.loads(args.actor_command_file.read_text())
    assert isinstance(command, list) and command and all(isinstance(x, str) for x in command)
    actor = live.Actor(command, args.actor_cwd)
    browser = None
    requests = []
    phase = 'none'
    try:
        def frame(revision):
            deadline = time.monotonic() + 120
            while True:
                header = actor.line(deadline)
                if header.startswith((b'FRAME ', b'PFRAME ')):
                    break
                if header.startswith((b'KEXE_TRAP', b'seed:', b'ACTOR_EXIT')):
                    raise RuntimeError(('native actor', header))
            fields = header.split(); is_page = fields[0] == b'PFRAME'
            assert len(fields) == (4 if is_page else 3) and int(fields[1]) == revision, header
            shell_size = int(fields[2]); page_size = int(fields[3]) if is_page else 0
            assert shell_size > 0 and shell_size + page_size <= 1048576 and (not is_page or page_size > 0)
            while len(actor.buf) < shell_size + page_size:
                actor.more(deadline)
            raw, actor.buf = actor.buf[:shell_size + page_size], actor.buf[shell_size + page_size:]
            return json.loads(raw[:shell_size]), json.loads(raw[shell_size:]) if is_page else None

        def send(raw):
            data = raw.encode('utf-8')
            actor.proc.stdin.write(str(len(data)).encode() + b'\n' + data); actor.proc.stdin.flush()

        first = frame(1)
        with tempfile.TemporaryDirectory(prefix='html-live-', dir=args.actor_cwd) as profile, ExitStack() as cleanup:
            browser = live.Pipe(args.chrome, profile)
            cleanup.callback(browser.close)
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url': 'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
            fixture = ROOT / 'test/browser-native/local_html_live.html'
            html = '<section><h1>Hi <em>世界</em></h1><p><strong>重要</strong> <code>x</code></p></section>'
            if args.broker:
                broker_bytes = args.broker.read_bytes()
                assert hashlib.sha256(broker_bytes).hexdigest() == '7f760128c3a58098123c31ec3dd34b9c871932e3933f2c952facc4ab0e647212'
                stage = Path(profile) / 'fixture'
                files = ['test/browser-native/local_html_broker_live.html', 'test/browser-native/local_html_live_test.js',
                         'test/browser-native/local_broker_response_test.js', 'src/browser/local_dom_host.js',
                         'src/browser/local_page_host.js', 'src/browser/local_html_response.js']
                for name in files:
                    dst = stage / name; dst.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(ROOT / name, dst)
                testdir = stage / 'test/browser-native'
                (testdir / 'canonical-client-runtime.js').write_bytes(broker_bytes)
                shutil.copyfile(ROOT / 'src/browser/local_html_response.js', testdir / 'local_html_response.js')
                fixture = testdir / 'local_html_broker_live.html'
                browser.call('Fetch.enable', {'patterns':[{'urlPattern':'https://*', 'requestStage':'Request'}]}, session)
            def pump():
                while browser.events:
                    event = browser.events.pop(0)
                    if event.get('method') != 'Fetch.requestPaused': continue
                    params = event['params']; request = params['request']; owner = event.get('sessionId')
                    requests.append({'phase':phase, 'url':request['url'], 'method':request['method'],
                                     'headers':sorted(k.lower() for k in request.get('headers', {}))})
                    if phase == 'allow' and owner == session and request['url'] == 'https://service.invalid/page' and request['method'] == 'GET':
                        browser.send('Fetch.fulfillRequest', {'requestId':params['requestId'], 'responseCode':200,
                            'body':base64.b64encode(html.encode()).decode(),
                            'responseHeaders':[{'name':'Content-Type','value':'text/html; charset=utf-8'},
                                               {'name':'Access-Control-Allow-Origin','value':'*'}]}, owner)
                    else:
                        browser.send('Fetch.failRequest', {'requestId':params['requestId'], 'errorReason':'BlockedByClient'}, owner)
            def evaluate(expression, await_promise=False):
                result = browser.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True, 'awaitPromise': await_promise}, session)
                pump()
                if 'exceptionDetails' in result:
                    raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
            browser.call('Page.navigate', {'url':fixture.as_uri()}, session)
            deadline = time.monotonic() + 30
            while not evaluate('typeof window.htmlLive === "object"'):
                if time.monotonic() >= deadline: raise TimeoutError('fixture load')
                time.sleep(.05)
            def present(pair):
                evaluate('window.htmlLive.present(' + json.dumps(pair[0], ensure_ascii=True) + ',' + json.dumps(pair[1], ensure_ascii=True) + ')')
            def take():
                deadline = time.monotonic() + 10
                while True:
                    value = evaluate('window.htmlLive.take()')
                    if value is not None: return value
                    if time.monotonic() >= deadline: raise TimeoutError('UI event')
                    time.sleep(.05)
            def advance(revision):
                send(str(revision - 1) + '|' + take()); value = frame(revision); present(value); return value
            def snap(): return evaluate('window.htmlLive.snapshot()')
            def click(selector):
                rect = evaluate('(()=>{const r=document.querySelector(' + json.dumps(selector) + ').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()')
                for kind in ('mousePressed', 'mouseReleased'):
                    browser.call('Input.dispatchMouseEvent', {'type':kind, 'x':rect['x'], 'y':rect['y'], 'button':'left', 'clickCount':1}, session)
            present(first)
            click('button[data-action=":tab/new"]'); advance(2)
            click('input'); url = 'https://service.invalid/page' if args.broker else 'https://one.example/'
            browser.call('Input.insertText', {'text':url}, session); advance(3)
            browser.call('Input.dispatchKeyEvent', {'type':'keyDown', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            browser.call('Input.dispatchKeyEvent', {'type':'keyUp', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            advance(4)
            # The wrong response is locally injected; the approved case below is intercepted.
            wrong = 'https://other.example/'
            send(f'H1|4|1|tab-1|{len(wrong.encode())}|{wrong}{html}')
            assert actor.line(time.monotonic() + 30) == b'REJECT'
            assert snap() == {'url':url, 'page':None, 'tabs':1}, snap()
            if args.broker:
                def broker_wait(predicate):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        state = evaluate('window.brokerFixture?.state() ?? null')
                        if state and predicate(state): return state
                        time.sleep(.05)
                    raise TimeoutError(('broker', state))
                broker_wait(lambda state: state['status'] == 'ready')
                phase = 'deny'; evaluate("window.brokerFixture.start('/page')")
                shown = broker_wait(lambda state: state['open'])
                assert 'https://service.invalid' in shown['scope'] and 'GET /page' in shown['scope']
                click('dialog.kotoba-client-consent button:first-of-type')
                denied = broker_wait(lambda state: state['status'] == 'error')
                assert 'not approved' in denied['message'] and not requests and snap()['page'] is None
                time.sleep(.25)
                phase = 'allow'; evaluate("window.brokerFixture.start('/page')"); broker_wait(lambda state: state['open'])
                evaluate("document.querySelectorAll('dialog.kotoba-client-consent button')[1].click()")
                assert evaluate('window.brokerFixture.state().open') and not requests
                click('dialog.kotoba-client-consent button:last-of-type')
                accepted = broker_wait(lambda state: state['status'] in ('ok', 'error'))
                assert accepted['status'] == 'ok', accepted
                assert len(requests) == 1 and requests[0]['url'] == url and requests[0]['method'] == 'GET'
                assert not any(h in requests[0]['headers'] for h in ('authorization', 'cookie', 'referer'))
                response = accepted['encoded']
            else:
                response = evaluate('window.htmlLive.encodeFixture(' + json.dumps(url) + ',' + json.dumps(html, ensure_ascii=True) + ')', True)
            header, payload = response.split('\n', 1)
            assert int(header) == len(payload.encode('utf-8')) and payload.startswith('H1|4|1|tab-1|')
            actor.proc.stdin.write(response.encode('utf-8')); actor.proc.stdin.flush()
            present(frame(5))
            assert snap() == {'url':url, 'page':'Hi 世界重要 x', 'tabs':1}, snap()
            click('input'); browser.call('Input.insertText', {'text':'x'}, session); present_frame = advance(6)
            assert present_frame[1] is not None and snap()['page'] == 'Hi 世界重要 x'
            click('button[data-action=":tab/new"]'); advance(7)
            assert snap()['page'] is None and snap()['tabs'] == 2
            click('button[data-tab-id="tab-1"]'); advance(8)
            assert snap()['page'] == 'Hi 世界重要 x' and snap()['tabs'] == 2
            actor.proc.stdin.write(b'0\n'); actor.proc.stdin.flush()
            tail, _ = actor.proc.communicate(timeout=30); actor.log.extend(tail)
            assert actor.proc.returncode == 0 and b':status :ok :result 0' in actor.log
            source_names = ['scripts/check-local-html-live.py', 'scripts/check-local-native-live.py',
                            'test/browser-native/local_html_live_test.js', 'src/browser/local_dom_host.js',
                            'src/browser/local_page_host.js', 'src/browser/local_html_response.js']
            source_names += (['test/browser-native/local_html_broker_live.html', 'test/browser-native/local_broker_response_test.js']
                             if args.broker else ['test/browser-native/local_html_live.html'])
            receipt = {'status':'PASS_OFFLINE_HTML_LIVE', 'chrome':version, 'frames':8,
                       'interceptedBroker':bool(args.broker), 'syntheticResponse':True, 'requests':requests, 'final':snap(),
                       'brokerSha256':hashlib.sha256(args.broker.read_bytes()).hexdigest() if args.broker else None,
                       'actorLogSha256':hashlib.sha256(actor.log).hexdigest(),
                       'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in source_names},
                       'limitations':(['automated consent clicks', 'intercepted fixture response'] if args.broker else ['synthetic response']) +
                                      ['no production host', 'no Kotoba layout', 'no origin isolation qualification']}
            if args.receipt: args.receipt.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(receipt, ensure_ascii=False))
    finally:
        if browser is not None: browser.close()
        actor.close()
        if args.log: args.log.write_bytes(actor.log)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--chrome', required=True)
    p.add_argument('--actor-command-file', required=True, type=Path)
    p.add_argument('--actor-cwd', required=True)
    p.add_argument('--log', type=Path)
    p.add_argument('--receipt', type=Path)
    p.add_argument('--broker', type=Path, help='pinned canonical broker for intercepted fixed endpoint')
    run(p.parse_args())
