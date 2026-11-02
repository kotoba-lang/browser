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
from urllib.parse import urlsplit

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
                if header.startswith((b'FRAME ', b'PFRAME ', b'NFRAME ')):
                    break
                if header.startswith((b'KEXE_TRAP', b'seed:', b'ACTOR_EXIT')):
                    raise RuntimeError(('native actor', header))
            fields = header.split(); is_page = fields[0] == b'PFRAME'; is_notice = fields[0] == b'NFRAME'
            assert len(fields) == (5 if is_notice else 4 if is_page else 3) and int(fields[1]) == revision, header
            shell_size = int(fields[2]); page_size = int(fields[3]) if is_page or is_notice else 0
            notice_size = int(fields[4]) if is_notice else 0
            size = shell_size + page_size + notice_size
            assert shell_size > 0 and size <= 1048576 and (not is_page or page_size > 0)
            while len(actor.buf) < size:
                actor.more(deadline)
            raw, actor.buf = actor.buf[:size], actor.buf[size:]
            return (json.loads(raw[:shell_size]), json.loads(raw[shell_size:shell_size + page_size]) if page_size else None,
                    json.loads(raw[shell_size + page_size:]) if is_notice else None, header + b'\n' + raw)

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
            # Exercise implicit paragraph and list-item endings in the native
            # parser before applying the resulting wire to the page host.
            html = '<!DOCTYPE html>\r\n<html><head><meta charset="utf-8"><title>猫\r\n犬</title></head><body><div><div><div><div><section><h1>Hi <em>世界</em></h1><p><strong>重要\r<!--猫 & < -->注意</strong> <code>x</code><ul><li>一<li>二</ul><hr><blockquote><small>Quote</small></blockquote><pre>\r\n  Code\n  Text</pre><a href="/next">Again</a><a href="javascript:alert(1)">Unsafe</a></section></div></div></div></div></body></html>'
            next_html = '<!doctype html><html><head><title>Next</title></head><body><section><h1>Next page</h1><p>Second document</p></section></body></html>'
            if args.broker:
                broker_bytes = args.broker.read_bytes()
                assert hashlib.sha256(broker_bytes).hexdigest() == '7f760128c3a58098123c31ec3dd34b9c871932e3933f2c952facc4ab0e647212'
                stage = Path(profile) / 'fixture'
                files = ['test/browser-native/local_html_broker_live.html', 'test/browser-native/local_html_live_test.js',
                         'test/browser-native/local_broker_response_test.js', 'src/browser/local_dom_host.js',
                         'src/browser/local_page_host.js', 'src/browser/local_actor_host.js', 'src/browser/local_html_response.js']
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
                    if phase.startswith('allow') and owner == session and request['url'] in ('https://service.invalid/page', 'https://service.invalid/next') and request['method'] == 'GET':
                        body = html if request['url'].endswith('/page') else next_html
                        browser.send('Fetch.fulfillRequest', {'requestId':params['requestId'], 'responseCode':200,
                            'body':base64.b64encode(body.encode()).decode(),
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
                evaluate('window.htmlLive.feed(' + json.dumps(list(pair[3])) + ')')
            def take():
                deadline = time.monotonic() + 10
                while True:
                    value = evaluate('window.htmlLive.take()')
                    if value is not None: return value
                    if time.monotonic() >= deadline: raise TimeoutError('UI event')
                    time.sleep(.05)
            def advance(revision):
                payload = take().encode('utf-8'); actor.proc.stdin.write(payload); actor.proc.stdin.flush()
                value = frame(revision); present(value); return value
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
            submitted = advance(4)
            assert submitted[2] == {'tab':'tab-1', 'generation':1, 'url':url}, submitted[2]
            assert evaluate('window.htmlLive.notice()') == dict(revision=4, **submitted[2])
            # The wrong response is locally injected; the approved case below is intercepted.
            wrong = 'https://other.example/'
            send(f'H1|4|1|tab-1|{len(wrong.encode())}|{wrong}{html}')
            assert actor.line(time.monotonic() + 30) == b'REJECT'
            assert snap() == {'url':url, 'page':None, 'tabs':1}, snap()
            response_revision, response_generation = 4, 1
            if args.broker:
                # The test-only bridge derives the fixed declared operation and
                # response context from the actor's notification. It is not a
                # shipping host or authentication of the actor channel.
                def broker_call(intent, revision):
                    parsed = urlsplit(intent['url'])
                    assert parsed.scheme == 'https' and parsed.netloc == 'service.invalid' and not parsed.query and not parsed.fragment
                    operation = json.dumps(parsed.path)
                    context = json.dumps({'revision':revision, 'generation':intent['generation'], 'tab':intent['tab']})
                    return 'window.brokerFixture.start(' + operation + ',' + context + ')'
                start_broker = broker_call(submitted[2], 4)
                def broker_wait(predicate):
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        state = evaluate('window.brokerFixture?.state() ?? null')
                        if state and predicate(state): return state
                        time.sleep(.05)
                    raise TimeoutError(('broker', state))
                broker_wait(lambda state: state['status'] == 'ready')
                phase = 'deny'; evaluate(start_broker)
                shown = broker_wait(lambda state: state['open'])
                assert 'https://service.invalid' in shown['scope'] and 'GET /page' in shown['scope']
                click('dialog.kotoba-client-consent button:first-of-type')
                denied = broker_wait(lambda state: state['status'] == 'error')
                assert 'not approved' in denied['message'] and not requests and snap()['page'] is None
                assert evaluate('window.htmlLive.cancel()') is True
                actor.proc.stdin.write(take().encode('utf-8')); actor.proc.stdin.flush()
                present(frame(5))
                assert snap() == {'url':'', 'page':None, 'tabs':1}, snap()
                send(f'H1|5|1|tab-1|{len(url.encode())}|{url}{html}')
                assert actor.line(time.monotonic() + 30) == b'REJECT'
                click('input'); browser.call('Input.insertText', {'text':url}, session); advance(6)
                browser.call('Input.dispatchKeyEvent', {'type':'keyDown', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
                browser.call('Input.dispatchKeyEvent', {'type':'keyUp', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
                retried = advance(7)
                assert retried[2] == {'tab':'tab-1', 'generation':2, 'url':url}, retried[2]
                assert evaluate('window.htmlLive.notice()') == dict(revision=7, **retried[2])
                # A synthetic unsupported HTML response is refused by the
                # native actor; the host must retain the frame and allow a
                # later approved response for the same pending navigation.
                assert evaluate('window.htmlLive.deliverFixture(' + json.dumps(url) + ',"<script>unsupported</script>")', True) is True
                actor.proc.stdin.write(take().encode('utf-8')); actor.proc.stdin.flush()
                assert actor.line(time.monotonic() + 30) == b'REJECT'
                evaluate('window.htmlLive.feed(' + json.dumps(list(b'REJECT\n')) + ')')
                refusals = evaluate('window.htmlLive.refusals()')
                assert len(refusals) == 1 and refusals[0]['operation'] == 'response' and refusals[0]['notice']['generation'] == 2
                assert snap() == {'url':url, 'page':None, 'tabs':1}, snap()
                response_revision, response_generation = 7, 2
                start_broker = broker_call(retried[2], response_revision)
                time.sleep(.25)
                phase = 'allow'; evaluate(start_broker); broker_wait(lambda state: state['open'])
                evaluate("document.querySelectorAll('dialog.kotoba-client-consent button')[1].click()")
                assert evaluate('window.brokerFixture.state().open') and not requests
                click('dialog.kotoba-client-consent button:last-of-type')
                accepted = broker_wait(lambda state: state['status'] in ('ok', 'error'))
                assert accepted['status'] == 'ok', accepted
                assert len(requests) == 1 and requests[0]['url'] == url and requests[0]['method'] == 'GET'
                assert not any(h in requests[0]['headers'] for h in ('authorization', 'cookie', 'referer'))
                assert evaluate('window.htmlLive.deliver(window.brokerFixture.takeResponse())', True) is True
                response = take()
            else:
                assert evaluate('window.htmlLive.deliverFixture(' + json.dumps(url) + ',' + json.dumps(html, ensure_ascii=True) + ')', True) is True
                response = take()
            header, payload = response.split('\n', 1)
            assert int(header) == len(payload.encode('utf-8')) and payload.startswith(f'H1|{response_revision}|{response_generation}|tab-1|')
            actor.proc.stdin.write(response.encode('utf-8')); actor.proc.stdin.flush()
            present(frame(response_revision + 1))
            assert snap() == {'url':url, 'page':'Hi 世界重要\n注意 x一二Quote  Code\n  TextAgainUnsafe', 'tabs':1}, snap()
            assert evaluate('window.htmlLive.tabTitle()') == '猫 犬'
            assert evaluate('window.htmlLive.commentNodes()') == [[3, '重要\n'], [8, '猫 & < '], [3, '注意']]
            assert evaluate('window.htmlLive.listItems()') == ['一', '二']
            assert evaluate('window.htmlLive.deepStructure()') is True
            assert evaluate('window.htmlLive.semanticElements()') is True
            assert evaluate('window.htmlLive.preformatted()') == {'text':'  Code\n  Text', 'whiteSpace':'pre'}
            current = response_revision + 1
            link = evaluate('window.htmlLive.linkRect()')
            assert link and not link['hasHref'] and evaluate('window.htmlLive.syntheticLink()') == 0
            if args.broker:
                unsafe = evaluate('window.htmlLive.linkRect(1)')
                assert unsafe and not unsafe['hasHref']
                for kind in ('mousePressed', 'mouseReleased'):
                    browser.call('Input.dispatchMouseEvent', {'type':kind, 'x':unsafe['x'], 'y':unsafe['y'], 'button':'left', 'clickCount':1}, session)
                assert evaluate('window.htmlLive.take()') is None and len(requests) == 1
                for kind in ('mousePressed', 'mouseReleased'):
                    browser.call('Input.dispatchMouseEvent', {'type':kind, 'x':link['x'], 'y':link['y'], 'button':'left', 'clickCount':1}, session)
                linked = advance(current + 1); current += 1
                next_url = 'https://service.invalid/next'
                assert linked[2] == {'tab':'tab-1', 'generation':3, 'url':next_url}, linked[2]
                assert evaluate('window.htmlLive.notice()') == dict(revision=current, **linked[2])
                phase = 'deny-link'; evaluate(broker_call(linked[2], current)); broker_wait(lambda state: state['open'])
                click('dialog.kotoba-client-consent button:first-of-type')
                denied = broker_wait(lambda state: state['status'] == 'error')
                assert 'not approved' in denied['message'] and len(requests) == 1
                assert evaluate('window.htmlLive.cancel()') is True
                actor.proc.stdin.write(take().encode('utf-8')); actor.proc.stdin.flush()
                current += 1; present(frame(current))
                assert evaluate('window.htmlLive.focusLink()') is True
                browser.call('Input.dispatchKeyEvent', {'type':'keyDown', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
                browser.call('Input.dispatchKeyEvent', {'type':'keyUp', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
                keyboard = advance(current + 1); current += 1
                assert keyboard[2] == {'tab':'tab-1', 'generation':4, 'url':next_url}, keyboard[2]
                def accept_notice(notice, revision, expected_url, expected_page, expected_title):
                    nonlocal phase
                    count = len(requests)
                    phase = 'allow-navigation'; evaluate(broker_call(notice, revision))
                    shown = broker_wait(lambda state: state['open'])
                    assert 'GET ' + urlsplit(expected_url).path in shown['scope'] and len(requests) == count
                    click('dialog.kotoba-client-consent button:last-of-type')
                    accepted = broker_wait(lambda state: state['status'] in ('ok', 'error'))
                    assert accepted['status'] == 'ok', accepted
                    assert len(requests) == count + 1 and requests[-1]['url'] == expected_url
                    assert evaluate('window.htmlLive.deliver(window.brokerFixture.takeResponse())', True) is True
                    actor.proc.stdin.write(take().encode('utf-8')); actor.proc.stdin.flush()
                    present(frame(revision + 1))
                    assert snap() == {'url':expected_url, 'page':expected_page, 'tabs':1}, snap()
                    assert evaluate('window.htmlLive.tabTitle()') == expected_title
                    return revision + 1
                current = accept_notice(keyboard[2], current, next_url, 'Next pageSecond document', 'Next')
                click('button[data-action=":history/back"]')
                back = advance(current + 1); current += 1
                assert back[2] == {'tab':'tab-1', 'generation':5, 'url':url}
                phase = 'deny-back'; evaluate(broker_call(back[2], current)); broker_wait(lambda state: state['open'])
                click('dialog.kotoba-client-consent button:first-of-type')
                denied = broker_wait(lambda state: state['status'] == 'error')
                assert 'not approved' in denied['message'] and len(requests) == 2
                assert evaluate('window.htmlLive.cancel()') is True
                actor.proc.stdin.write(take().encode('utf-8')); actor.proc.stdin.flush()
                current += 1; present(frame(current))
                assert snap() == {'url':next_url, 'page':'Next pageSecond document', 'tabs':1}
                click('button[data-action=":history/back"]')
                back = advance(current + 1); current += 1
                assert back[2] == {'tab':'tab-1', 'generation':6, 'url':url}
                current = accept_notice(back[2], current, url, 'Hi 世界重要\n注意 x一二Quote  Code\n  TextAgainUnsafe', '猫 犬')
                click('button[data-action=":history/forward"]')
                forward = advance(current + 1); current += 1
                assert forward[2] == {'tab':'tab-1', 'generation':7, 'url':next_url}
                current = accept_notice(forward[2], current, next_url, 'Next pageSecond document', 'Next')
                click('button[data-action=":history/back"]')
                back = advance(current + 1); current += 1
                assert back[2] == {'tab':'tab-1', 'generation':8, 'url':url}
                current = accept_notice(back[2], current, url, 'Hi 世界重要\n注意 x一二Quote  Code\n  TextAgainUnsafe', '猫 犬')
                assert len(requests) == 5 and all(not any(h in request['headers'] for h in ('authorization','cookie','referer')) for request in requests)
            click('input'); browser.call('Input.insertText', {'text':'x'}, session); present_frame = advance(current + 1)
            assert present_frame[1] is not None and snap()['page'] == 'Hi 世界重要\n注意 x一二Quote  Code\n  TextAgainUnsafe'
            click('button[data-action=":tab/new"]'); advance(current + 2)
            assert snap()['page'] is None and snap()['tabs'] == 2
            click('button[data-tab-id="tab-1"]'); advance(current + 3)
            assert snap()['page'] == 'Hi 世界重要\n注意 x一二Quote  Code\n  TextAgainUnsafe' and snap()['tabs'] == 2
            actor.proc.stdin.write(b'0\n'); actor.proc.stdin.flush()
            tail, _ = actor.proc.communicate(timeout=30); actor.log.extend(tail)
            assert actor.proc.returncode == 0 and b':status :ok :result 0' in actor.log
            source_names = ['scripts/check-local-html-live.py', 'scripts/check-local-native-live.py',
                            'test/browser-native/local_html_live_test.js', 'src/browser/local_dom_host.js', 'src/browser/local_actor_host.js',
                            'src/browser/local_page_host.js', 'src/browser/local_html_response.js']
            source_names += (['test/browser-native/local_html_broker_live.html', 'test/browser-native/local_broker_response_test.js']
                             if args.broker else ['test/browser-native/local_html_live.html'])
            receipt = {'status':'PASS_OFFLINE_HTML_LIVE', 'chrome':version, 'frames':current + 3,
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
