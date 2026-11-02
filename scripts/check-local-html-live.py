#!/usr/bin/env python3
"""Offline Chrome UI -> native HTML actor -> Chrome page integration test."""
import argparse
import importlib.util
import json
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
        with tempfile.TemporaryDirectory(prefix='html-live-') as profile:
            browser = live.Pipe(args.chrome, profile)
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url': 'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
            browser.call('Page.navigate', {'url': (ROOT / 'test/browser-native/local_html_live.html').as_uri()}, session)
            def evaluate(expression, await_promise=False):
                result = browser.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True, 'awaitPromise': await_promise}, session)
                if 'exceptionDetails' in result:
                    raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
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
            click('input'); url = 'https://one.example/'
            browser.call('Input.insertText', {'text':url}, session); advance(3)
            browser.call('Input.dispatchKeyEvent', {'type':'keyDown', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            browser.call('Input.dispatchKeyEvent', {'type':'keyUp', 'key':'Enter', 'code':'Enter', 'windowsVirtualKeyCode':13}, session)
            advance(4)
            html = '<section><h1>Hi <em>世界</em></h1><p><strong>重要</strong> <code>x</code></p></section>'
            # These are injected offline fixtures, not approved network responses.
            wrong = 'https://other.example/'
            send(f'H1|4|1|tab-1|{len(wrong.encode())}|{wrong}{html}')
            assert actor.line(time.monotonic() + 30) == b'REJECT'
            assert snap() == {'url':url, 'page':None, 'tabs':1}, snap()
            response = evaluate('window.htmlLive.encodeFixture(' + json.dumps(url) + ',' + json.dumps(html, ensure_ascii=True) + ')', True)
            header, payload = response.split('\n', 1)
            assert int(header) == len(payload.encode('utf-8')) and payload.startswith('H1|4|1|tab-1|')
            actor.proc.stdin.write(response.encode('utf-8')); actor.proc.stdin.flush()
            present(frame(5))
            assert snap() == {'url':url, 'page':'Hi 世界重要 x', 'tabs':1}, snap()
            browser.call('Input.insertText', {'text':'x'}, session); present_frame = advance(6)
            assert present_frame[1] is not None and snap()['page'] == 'Hi 世界重要 x'
            click('button[data-action=":tab/new"]'); advance(7)
            assert snap()['page'] is None and snap()['tabs'] == 2
            click('button[data-tab-id="tab-1"]'); advance(8)
            assert snap()['page'] == 'Hi 世界重要 x' and snap()['tabs'] == 2
            actor.proc.stdin.write(b'0\n'); actor.proc.stdin.flush()
            tail, _ = actor.proc.communicate(timeout=30); actor.log.extend(tail)
            assert actor.proc.returncode == 0 and b':status :ok :result 0' in actor.log
            print(json.dumps({'status':'PASS_OFFLINE_HTML_LIVE', 'chrome':version, 'frames':8,
                              'syntheticResponse':True, 'final':snap()}, ensure_ascii=False))
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
    run(p.parse_args())
