#!/usr/bin/env python3
"""Offline test bridge between a real actor process and Chrome; not an app host."""
import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import select
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]

class Pipe:
    def __init__(self, browser, profile, host_rules='MAP * ~NOTFOUND', browser_args=()):
        to_r, self.to_w = os.pipe()
        self.from_r, from_w = os.pipe()
        def fds():
            a, b = os.dup(to_r), os.dup(from_w)
            os.dup2(a, 3)
            os.dup2(b, 4)
        args = [browser, '--headless', '--disable-gpu', '--no-first-run',
                '--disable-extensions', '--disable-background-networking', '--disable-sync',
                f'--host-resolver-rules={host_rules}', '--allow-file-access-from-files',
                '--remote-debugging-pipe', f'--user-data-dir={profile}', 'about:blank']
        args[1:1] = list(browser_args)
        if os.geteuid() == 0 or os.environ.get('KOTOBA_BROWSER_NO_SANDBOX') == '1':
            args.insert(1, '--no-sandbox')
        self.log = open(Path(profile) / 'browser.log', 'w+b')
        self.proc = subprocess.Popen(args, preexec_fn=fds, close_fds=False,
                                     stdout=subprocess.DEVNULL, stderr=self.log)
        os.close(to_r)
        os.close(from_w)
        self.buf = b''
        self.next_id = 0
        self.events = []

    def call(self, method, params=None, session=None, timeout=20):
        self.next_id += 1
        request = {'id': self.next_id, 'method': method, 'params': params or {}}
        if session:
            request['sessionId'] = session
        os.write(self.to_w, json.dumps(request).encode() + b'\0')
        deadline = time.monotonic() + timeout
        while True:
            while b'\0' in self.buf:
                raw, self.buf = self.buf.split(b'\0', 1)
                reply = json.loads(raw)
                if 'id' not in reply:
                    self.events.append(reply)
                    continue
                if reply.get('id') == self.next_id:
                    if 'error' in reply:
                        raise RuntimeError((method, reply['error']))
                    return reply['result']
            left = deadline - time.monotonic()
            if left <= 0 or not select.select([self.from_r], [], [], left)[0]:
                raise TimeoutError(method)
            chunk = os.read(self.from_r, 65536)
            if not chunk:
                raise RuntimeError('Chrome pipe closed')
            self.buf += chunk

    def send(self, method, params=None, session=None):
        self.next_id += 1
        request = {'id': self.next_id, 'method': method, 'params': params or {}}
        if session:
            request['sessionId'] = session
        os.write(self.to_w, json.dumps(request).encode() + b'\0')

    def close(self):
        if getattr(self, 'closed', False):
            return
        self.closed = True
        try:
            self.call('Browser.close', timeout=5)
        except Exception:
            self.proc.terminate()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()
        os.close(self.to_w)
        os.close(self.from_r)
        self.log.close()

class Actor:
    def __init__(self, command, cwd):
        self.proc = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
        self.buf = b''
        self.log = bytearray()

    def more(self, deadline):
        left = deadline - time.monotonic()
        if left <= 0 or not select.select([self.proc.stdout], [], [], left)[0]:
            raise TimeoutError(('actor', bytes(self.log[-1000:])))
        chunk = os.read(self.proc.stdout.fileno(), 65536)
        if not chunk:
            raise RuntimeError(('actor EOF', bytes(self.log[-1000:])))
        self.buf += chunk
        self.log.extend(chunk)

    def line(self, deadline):
        while b'\n' not in self.buf:
            self.more(deadline)
        line, self.buf = self.buf.split(b'\n', 1)
        return line.rstrip(b'\r')

    def frame(self, revision):
        deadline = time.monotonic() + 60
        while True:
            line = self.line(deadline)
            if line.startswith(b'FRAME '):
                break
            if line.startswith((b'KEXE_TRAP', b'seed:', b'ACTOR_EXIT')):
                raise RuntimeError(('actor startup', line))
        fields = line.split()
        assert len(fields) == 3 and int(fields[1]) == revision, line
        size = int(fields[2])
        assert 0 < size <= 1048576
        while len(self.buf) < size:
            self.more(deadline)
        raw, self.buf = self.buf[:size], self.buf[size:]
        json.loads(raw)  # Validate the actor frame before forwarding exact bytes.
        return raw

    def send(self, revision, wire):
        payload = (str(revision) + '|' + wire).encode()
        self.proc.stdin.write(str(len(payload)).encode() + b'\n' + payload)
        self.proc.stdin.flush()

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()


def run(args):
    command = json.loads(Path(args.actor_command_file).read_text())
    assert isinstance(command, list) and command and all(isinstance(x, str) for x in command)
    actor = Actor(command, args.actor_cwd)
    browser = None
    try:
        first = actor.frame(1)
        with tempfile.TemporaryDirectory(prefix='browser-live-') as profile, ExitStack() as cleanup:
            browser = Pipe(args.chrome, profile)
            cleanup.callback(browser.close)
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url': (ROOT / 'test/browser-native/local_native_live.html').as_uri()})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
            def evaluate(expression):
                result = browser.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True}, session)
                if 'exceptionDetails' in result:
                    raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
            deadline = time.monotonic() + 20
            while not evaluate('typeof window.nativeLive === "object"'):
                if time.monotonic() >= deadline:
                    raise TimeoutError('fixture did not load')
                time.sleep(.05)
            def present(rev, payload):
                frame = f'FRAME {rev} {len(payload)}\n'.encode() + payload
                # Split headers, payload and potentially UTF-8 code points across messages.
                for chunk in (frame[:7], frame[7:-1], frame[-1:]):
                    evaluate('window.nativeLive.feed(' + json.dumps(list(chunk)) + ')')
            def event():
                deadline = time.monotonic() + 10
                while True:
                    value = evaluate('window.nativeLive.take()')
                    if value is not None:
                        return value
                    if time.monotonic() >= deadline:
                        raise TimeoutError('UI event')
                    time.sleep(.05)
            def click(selector):
                rect = evaluate('(()=>{const r=document.querySelector(' + json.dumps(selector) + ').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()')
                for kind in ('mousePressed', 'mouseReleased'):
                    browser.call('Input.dispatchMouseEvent', dict(type=kind, x=rect['x'], y=rect['y'], button='left', clickCount=1), session)
            def advance(rev):
                e = event()
                header, payload = e.split('\n', 1)
                assert int(header) == len(payload.encode()) and payload.startswith(str(rev - 1) + '|B1|'), e
                actor.proc.stdin.write(e.encode())
                actor.proc.stdin.flush()
                payload = actor.frame(rev)
                present(rev, payload)
                return payload
            def snap():
                return evaluate('window.nativeLive.snapshot()')
            first_frame = f'FRAME 1 {len(first)}\n'.encode() + first
            assert evaluate('window.nativeLive.negative(' + json.dumps(list(first_frame)) + ')') is True
            assert evaluate('window.nativeLive.compositionCleanup(' + json.dumps(list(first_frame)) + ')') is True
            present(1, first)
            click('button')
            click('button')
            assert evaluate('window.nativeLive.busy()') == 1
            second = advance(2)
            assert evaluate('window.nativeLive.take()') is None
            second_frame = f'FRAME 2 {len(second)}\n'.encode() + second
            assert evaluate('window.nativeLive.queueLimit(' + json.dumps(list(first_frame)) + ',' + json.dumps(list(second_frame)) + ')') is True
            click('input')
            # Three browser input events arrive before any actor response.
            for text in ('A', '😀', 'B'):
                browser.call('Input.insertText', {'text': text}, session)
            advance(3)
            advance(4)
            advance(5)
            assert snap()['value'] == 'A😀B', snap()
            actor.send(1, 'B1|:ui/click|3|-1|-1|0|0|')
            assert actor.line(time.monotonic() + 10) == b'REJECT'
            assert snap()['revision'] == 5
            evaluate("document.querySelector('input').setSelectionRange(1,3,'backward')")
            browser.call('Input.insertText', {'text': 'X'}, session)
            advance(6)
            state = snap()
            assert state == {'revision': 6, 'value': 'AXB', 'focused': True, 'start': 2, 'end': 2}, state
            browser.call('Input.insertText', {'text': '日本'}, session)
            advance(7)
            state = snap()
            assert state == {'revision': 7, 'value': 'AX日本B', 'focused': True, 'start': 4, 'end': 4}, state
            evaluate('window.nativeLive.captureNode()')
            # Synthetic composition events test protocol ordering, not a real IME.
            evaluate("(()=>{const e=document.querySelector('input'); for(const [type,data] of [['compositionstart',''],['compositionupdate','語'],['compositionend','語']]) e.dispatchEvent(new CompositionEvent(type,{bubbles:true,data}));})()")
            advance(8)
            assert evaluate('window.nativeLive.sameNode()') is True
            advance(9)
            assert evaluate('window.nativeLive.sameNode()') is True
            advance(10)
            assert evaluate('window.nativeLive.sameNode()') is False
            state = snap()
            assert state == {'revision': 10, 'value': 'AX日本語B', 'focused': True, 'start': 5, 'end': 5}, state
            actor.proc.stdin.write(b'0\n')
            actor.proc.stdin.flush()
            tail, _ = actor.proc.communicate(timeout=30)
            actor.log.extend(tail)
            assert actor.proc.returncode == 0, bytes(actor.log[-1000:])
            assert b':status :ok :result 0' in actor.log, bytes(actor.log[-1000:])
            print(json.dumps({'chrome': version, 'frames': 10, 'stale_rejected': True, 'synthetic_composition': True, 'final': state}, ensure_ascii=False))
    finally:
        if browser is not None:
            browser.close()
        actor.close()
        if args.log:
            Path(args.log).write_bytes(actor.log)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--chrome', required=True)
    parser.add_argument('--actor-command-file', required=True, help='JSON array of test actor command arguments')
    parser.add_argument('--actor-cwd', required=True)
    parser.add_argument('--log')
    run(parser.parse_args())
