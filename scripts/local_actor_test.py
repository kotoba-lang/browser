"""Exercise one native actor over live pipes; the Kotoba guest owns state."""
import json
import os
from pathlib import Path
import select
import subprocess
import time


def run_actor(command, env, output, timeout=60):
    process = subprocess.Popen(list(map(str, command)), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, env=env, bufsize=0)
    buf = b''
    observed = bytearray()

    def read_more(deadline):
        nonlocal buf
        timeout = deadline - time.monotonic()
        if timeout <= 0 or not select.select([process.stdout], [], [], timeout)[0]:
            raise AssertionError(('actor timeout', bytes(observed[-1000:])))
        chunk = os.read(process.stdout.fileno(), 65536)
        if not chunk:
            raise AssertionError(('actor EOF', bytes(observed[-1000:])))
        buf += chunk
        observed.extend(chunk)

    def line(deadline):
        nonlocal buf
        while b'\n' not in buf:
            read_more(deadline)
        value, buf = buf.split(b'\n', 1)
        return value.rstrip(b'\r')

    def frame(revision):
        nonlocal buf
        deadline = time.monotonic() + timeout
        # A direct actor starts with FRAME; the optional startup lines also
        # permit the same test to run under an offline QEMU boot harness.
        while True:
            header = line(deadline)
            if header.startswith(b'FRAME '):
                break
            if header.startswith((b'KEXE_TRAP', b'seed:', b'ACTOR_EXIT')):
                raise AssertionError(('actor startup', header))
        fields = header.split()
        assert len(fields) == 3 and int(fields[1]) == revision
        size = int(fields[2])
        assert 0 < size <= 1048576
        while len(buf) < size:
            read_more(deadline)
        raw, buf = buf[:size], buf[size:]
        return json.loads(raw)

    def send(revision, event):
        payload = (str(revision) + '|' + event).encode('utf-8')
        process.stdin.write(str(len(payload)).encode() + b'\n' + payload)
        process.stdin.flush()

    def address(batches):
        ops = [op for batch in batches for op in batch]
        return next(op[3] for op in ops if op[0] == ':dom/set-attr' and op[2] == ':value')

    try:
        assert address(frame(1)) == ''
        send(1, 'B1|:ui/click|3|-1|-1|0|0|')
        assert address(frame(2)) == ''
        send(2, 'B1|:ui/text|9|0|0|0|0|A😀B')
        assert address(frame(3)) == 'A😀B'
        send(1, 'B1|:ui/click|3|-1|-1|0|0|')
        assert line(time.monotonic() + timeout) == b'REJECT'
        send(3, 'B1|:ui/text|9|3|1|0|0|X')
        assert address(frame(4)) == 'AXB'
        send(4, 'B1|:ui/text|9|2|2|0|0||日本\n')
        assert address(frame(5)) == 'AX|日本\nB'
        process.stdin.write(b'0\n')
        process.stdin.flush()
        tail, errors = process.communicate(timeout=timeout)
        observed.extend(tail)
        assert process.returncode == 0 and not errors
        assert b':status :ok :result 0' in bytes(observed)
    finally:
        Path(output).write_bytes(observed)
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)

    invalid = subprocess.run(list(map(str, command)), input=b'01\n', stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, env=env, timeout=timeout)
    assert b':status :ok :result 2' in invalid.stdout and b'FRAME 2 ' not in invalid.stdout
    denied = subprocess.run(list(map(str, command)), input=b'0\n', stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=dict(env, KEXE_ALLOW='37,39'), timeout=timeout)
    combined = denied.stdout + denied.stderr
    assert b'KEXE_CAP' in combined and b':wire 41' in combined and b':refused' in combined
    assert b'FRAME 2 ' not in combined
    Path(str(output) + '.invalid').write_bytes(invalid.stdout + invalid.stderr)
    Path(str(output) + '.denied').write_bytes(combined)
