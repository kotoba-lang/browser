"""Exercise the experimental HTML actor over local pipes; peer authentication is not implemented."""
import json
import os
from pathlib import Path
import select
import subprocess
import time


def run_actor(command, env, output, timeout=90):
    process = subprocess.Popen(list(map(str, command)), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, env=env, bufsize=0)
    buffer = b''
    observed = bytearray()

    def more(deadline):
        nonlocal buffer
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([process.stdout], [], [], remaining)[0]:
            raise AssertionError(('actor timeout', bytes(observed[-1000:])))
        data = os.read(process.stdout.fileno(), 65536)
        if not data:
            raise AssertionError(('actor EOF', bytes(observed[-1000:])))
        buffer += data
        observed.extend(data)

    def line(deadline):
        nonlocal buffer
        while b'\n' not in buffer:
            more(deadline)
        value, buffer = buffer.split(b'\n', 1)
        return value.rstrip(b'\r')

    def frame(revision, page=False, notice=None):
        nonlocal buffer
        deadline = time.monotonic() + timeout
        header = line(deadline)
        fields = header.split()
        assert fields[0] == (b'NFRAME' if notice else b'PFRAME' if page else b'FRAME') and int(fields[1]) == revision, header
        assert len(fields) == (5 if notice else 4 if page else 3)
        shell_len = int(fields[2]); page_len = int(fields[3]) if page or notice else 0
        intent_len = int(fields[4]) if notice else 0
        size = shell_len + page_len + intent_len
        assert 0 < shell_len and size <= 1048576 and (not page or page_len > 0)
        while len(buffer) < size:
            more(deadline)
        raw, buffer = buffer[:size], buffer[size:]
        if notice: assert json.loads(raw[shell_len + page_len:]) == notice
        return json.loads(raw[:shell_len]), json.loads(raw[shell_len:shell_len + page_len]) if page else None

    def send(payload):
        raw = payload.encode('utf-8')
        process.stdin.write(str(len(raw)).encode() + b'\n' + raw)
        process.stdin.flush()

    def event(rev, wire):
        send(str(rev) + '|' + wire)

    def response(rev, generation, tab, url, body):
        send(f'H1|{rev}|{generation}|{tab}|{len(url.encode())}|{url}{body}')

    def address(shell):
        return next(op[3] for batch in shell for op in batch if op[0] == ':dom/set-attr' and op[2] == ':value')

    def reject():
        assert line(time.monotonic() + timeout) == b'REJECT'

    try:
        assert address(frame(1)[0]) == ''
        event(1, 'B1|:ui/click|3|-1|-1|0|0|'); frame(2)
        url = 'https://one.example/'
        event(2, 'B1|:ui/text|9|0|0|0|0|' + url); assert address(frame(3)[0]) == url
        event(3, 'B1|:ui/submit|9|-1|-1|0|0|'); frame(4, notice={'tab':'tab-1', 'generation':1, 'url':url})
        body = '<section><h1>Hi <em>世界</em></h1><p><strong>重要</strong> <code>x</code></p></section>'
        response(3, 1, 'tab-1', url, body); reject()
        response(4, 1, 'tab-1', 'https://other.example/', body); reject()
        response(4, 1, 'tab-1', url, '<script>x</script>'); reject()
        send('H1|4|'); reject()
        send('H1|4|1|tab-1|1|😀'); reject()  # byte boundary inside a UTF-8 scalar
        response(4, 1, 'tab-1', url, body)
        shell, page = frame(5, True)
        assert address(shell) == url and any(op[0] == ':dom/create-element' and op[2] == ':h1' for batch in page for op in batch)
        response(5, 1, 'tab-1', url, body); reject()
        event(5, 'B1|:ui/text|9|20|20|0|0|x'); shell, after = frame(6, True)
        assert address(shell) == url + 'x' and after == page
        event(6, 'B1|:ui/click|5|-1|-1|0|0|'); frame(7)  # open a second blank tab
        event(7, 'B1|:ui/click|3|-1|-1|0|0|'); _, restored = frame(8, True)
        assert restored == page
        process.stdin.write(b'0\n'); process.stdin.flush()
        tail, errors = process.communicate(timeout=timeout)
        observed.extend(tail)
        assert process.returncode == 0 and not errors and b':status :ok :result 0' in observed
    finally:
        Path(output).write_bytes(observed)
        if process.poll() is None:
            process.kill(); process.wait(timeout=10)

    invalid = subprocess.run(list(map(str, command)), input=b'01\n', stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env, timeout=timeout)
    assert b':status :ok :result 2' in invalid.stdout and b'PFRAME ' not in invalid.stdout
    denied = subprocess.run(list(map(str, command)), input=b'0\n', stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=dict(env, KEXE_ALLOW='37,39'), timeout=timeout)
    combined = denied.stdout + denied.stderr
    assert b':wire 41' in combined and b':refused' in combined and b'PFRAME ' not in combined
    Path(str(output) + '.invalid').write_bytes(invalid.stdout + invalid.stderr)
    Path(str(output) + '.denied').write_bytes(combined)
