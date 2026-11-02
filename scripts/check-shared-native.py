#!/usr/bin/env python3
"""Compile and execute actual shared modules on a native Linux kernel.

Python stages source locks and observes processes. Amu emits every artifact;
the checked-in Kotoba fixtures own the verdict. No hosted compiler or loader.
The CSSOM/surface graph and a kernel desktop are separate, unqualified gates.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import time
from pathlib import Path
sys.dont_write_bytecode = True
from local_actor_test import run_actor
from local_html_actor_test import run_actor as run_html_actor


def pin(path):
    data = Path(path).read_bytes()
    return dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def main(a):
    machine = platform.machine()
    assert platform.system() == 'Linux'
    assert machine in ({'aarch64', 'arm64'} if a.target == 'arm64' else {'x86_64', 'AMD64'})
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    roots = [a.browser.resolve(), a.dom.resolve(), a.text.resolve()]
    calls, cases = [], []
    compiler = a.compiler.resolve()
    scope = ':'.join(map(str, [out, *roots, *([a.html.resolve()] if a.html else [])]))
    env = dict(PATH='/nonexistent', KEXE_STACK_BYTES='8388608', KEXE_CAP_RESOURCES_35=scope)
    runner_source = out / 'runner.kotoba'
    if a.target == 'arm64':
        runner_source.write_bytes(a.runner_source.read_bytes())

    def run(name, args, custom=None, ok=True):
        start = time.monotonic()
        result = subprocess.run(list(map(str, args)), env=env if custom is None else custom,
                                cwd=out, capture_output=True, timeout=900)
        (out / (name + '.stdout')).write_bytes(result.stdout)
        (out / (name + '.stderr')).write_bytes(result.stderr)
        calls.append(dict(name=name, argv=list(map(str, args)), exit=result.returncode,
                          seconds=round(time.monotonic() - start, 3)))
        (out / 'calls.json').write_text(json.dumps(calls, indent=2) + '\n')
        print(name, result.returncode, result.stderr.decode(errors='replace')[:500], flush=True)
        if ok:
            assert result.returncode == 0 and not result.stderr, (name, result.returncode, result.stderr[-1000:])
        return result

    test_cases = [('text', a.text, 'text.kotoba'),
                                   ('dom', a.dom, 'dom.kotoba'),
                                   ('text-edit', a.browser, 'text-edit.kotoba'),
                                   ('chrome', a.browser, 'chrome.kotoba'),
                                   ('origin', a.browser, 'origin.kotoba'),
                                   ('storage', a.browser, 'storage.kotoba'),
                                   ('local-session', a.browser, 'local_session.kotoba'),
                                   ('local-snapshot', a.browser, 'local_snapshot.kotoba'),
                                   ('local-view', a.browser, 'local_view.kotoba'),
                                   ('local-text-view', a.browser, 'local_text_view.kotoba'),
                                   ('local-multitab-text', a.browser, 'local_multitab_text.kotoba'),
                                   ('local-dom-replay', a.browser, 'local_dom_replay.kotoba'),
                                   ('local-dom-commit', a.browser, 'local_dom_commit.kotoba'),
                                   ('local-controller', a.browser, 'local_controller.kotoba'),
                                   ('local-dom-events', a.browser, 'local_dom_events.kotoba'),
                                   ('local-loop', a.browser, 'local_loop.kotoba')]
    # The wire fixture writes its result through the explicitly granted local
    # stdout capability. The x86 scalar project runner does not support it.
    if a.case == 'local-snapshot-codec-boundary':
        test_cases.append(('local-snapshot-codec-boundary', a.browser, 'local_snapshot_codec_boundary.kotoba'))
    if a.case == 'local-snapshot-codec-limit':
        test_cases.append(('local-snapshot-codec-limit', a.browser, 'local_snapshot_codec_limit.kotoba'))
    if a.case == 'local-snapshot-codec':
        test_cases.append(('local-snapshot-codec', a.browser, 'local_snapshot_codec.kotoba'))
    if a.case == 'local-snapshot-read':
        assert a.target == 'arm64' and a.event_wire
        test_cases.append(('local-snapshot-read', a.browser, 'local_snapshot_read.kotoba'))
    if a.case == 'local-snapshot-wire':
        assert a.target == 'arm64'
        test_cases.append(('local-snapshot-wire', a.browser, 'local_snapshot_wire.kotoba'))
    if a.case == 'local-dom-wire':
        assert a.target == 'arm64'
        test_cases.append(('local-dom-wire', a.browser, 'local_dom_wire.kotoba'))
    if a.case == 'local-loop-input':
        assert a.target == 'arm64' and a.event_wire
        test_cases.append(('local-loop-input', a.browser, 'local_loop_input.kotoba'))
    if a.case in ('local-html-navigation', 'local-html-loop', 'local-html-frame'):
        assert a.target == 'arm64' and a.html
        test_cases.append((a.case, a.browser, a.case.replace('-', '_') + '.kotoba'))
    if a.case in ('local-native-actor', 'local-html-actor'):
        assert a.target == 'arm64' and (a.case != 'local-html-actor' or a.html)
        test_cases.append((a.case, a.browser, a.case.replace('-', '_') + '.kotoba'))
    for name, owner, filename in test_cases:
        if a.case and name != a.case:
            continue
        entry = (owner / ('src/browser' if name in ('local-native-actor', 'local-html-actor') else 'test/native') / filename).resolve()
        source_roots = [root / 'src' for root in roots] + [a.browser.resolve() / 'kotoba', entry.parent]
        if a.case in ('local-html-navigation', 'local-html-loop', 'local-html-frame', 'local-html-actor'):
            source_roots.append(a.html.resolve() / 'kotoba')
        flags = [part for root in source_roots for part in ['--source-path', str(root)]]
        discovered = run(name + '-modules', [compiler, 'modules', entry, *flags])
        files = [Path(line.split(' ', 1)[1]) for line in discovered.stdout.decode().splitlines()]
        assert entry in files and len(files) == len(set(files))
        assert all(any(path.is_relative_to(root) for root in source_roots) for path in files)
        lock = out / (name + '.ksl')
        lock.write_text('KSL1\nentry ' + str(entry) + '\n' +
                        ''.join('root ' + str(root) + '\n' for root in source_roots) +
                        ''.join('file ' + pin(path)['sha256'] + ' ' + str(path) + '\n' for path in sorted(files)))
        flags += ['--source-lock', str(lock), '--source-lock-sha256', pin(lock)['sha256']]
        run(name + '-check', [compiler, 'check', entry, *flags])
        artifact = out / (name + ('.kseed' if a.target == 'arm64' else '.kx64'))
        args = [compiler, 'compile', entry, '--target',
                'aarch64-macos' if a.target == 'arm64' else 'x86_64-linux', '--output', artifact]
        if a.target == 'x86_64':
            args += ['--format', 'kx64-v1']
        run(name + '-compile', args + flags)
        if a.target == 'arm64':
            image = out / (name + '.elf')
            run(name + '-pack', [compiler, 'compile', runner_source, '--target', 'aarch64-linux',
                                 '--output', image, '--format', 'packed', '--payload', artifact])
            image.chmod(0o755)
            invoke = [image]
        else:
            header, body = artifact.read_bytes().split(b'\n\n', 1)
            match = re.fullmatch(rb'KX64V1 x86_64-linux sysv-ctx-v11 ([0-9]+) 0', header)
            assert match and int(match[1]) == len(body) and body[0] == 0xe9
            image = out / (name + '.bin')
            image.write_bytes(body)  # Unchanged compiler-emitted bytes, never an image writer.
            invoke = [a.runner.resolve(), image, 0, 0, 'x86_64', '-']
        if name in ('local-loop-input', 'local-snapshot-read'):
            wire = a.event_wire.read_text(encoding='utf-8')
            assert len(wire.encode('utf-8')) <= (131072 if name == 'local-snapshot-read' else 65664)
            invoke += [wire]
        allow = '38' if name == 'local-snapshot-read' else ('37,39' if name == 'local-html-frame' else ('37' if name in ('local-dom-wire', 'local-html-navigation', 'local-html-loop', 'local-snapshot-wire') else ('37,38' if name == 'local-loop-input' else '-')))
        if name in ('local-native-actor', 'local-html-actor'):
            (run_html_actor if name == 'local-html-actor' else run_actor)(invoke, dict(env, KEXE_STRUCTURED_REPORT='1', KEXE_ALLOW='37,39,41'),
                      out / (name + '-execute.stdout'))
        else:
            result = run(name + '-execute', invoke, dict(env, KEXE_STRUCTURED_REPORT='1', KEXE_ALLOW=allow))
            match = re.search(rb':result (-?[0-9]+)', result.stdout)
            assert match and int(match[1]) == 0, (name, result.stdout)
        if name == 'local-snapshot-wire':
            lines = [line[5:] for line in result.stdout.splitlines() if line.startswith(b'SNAP ')]
            assert len(lines) == 1 and lines[0].startswith(b'BSS1|') and len(lines[0]) <= 131072
            (out / 'snapshot.bss1').write_bytes(lines[0])
            denied = run(name + '-denied', invoke, dict(env, KEXE_STRUCTURED_REPORT='1', KEXE_ALLOW='-'), ok=False)
            assert b':wire 37 :refused :not-granted' in denied.stdout + denied.stderr
            assert not any(line.startswith(b'SNAP ') for line in denied.stdout.splitlines())
        if name == 'local-dom-wire':
            lines = [line[6:] for line in result.stdout.splitlines() if line.startswith(b'WIRE1 ')]
            assert len(lines) == 1 and isinstance(json.loads(lines[0]), list)
            (out / 'wire.json').write_bytes(lines[0])
        if name == 'local-html-frame':
            match = re.search(rb'PFRAME ([1-9][0-9]*) ([1-9][0-9]*) ([1-9][0-9]*)\n', result.stdout)
            assert match
            shell_length, page_length = int(match[2]), int(match[3])
            end = match.end() + shell_length + page_length
            assert end <= len(result.stdout)
            assert isinstance(json.loads(result.stdout[match.end():match.end() + shell_length]), list)
            assert isinstance(json.loads(result.stdout[match.end() + shell_length:end]), list)
            (out / 'pageframe.bin').write_bytes(result.stdout[match.start():end])
            denied = run(name + '-denied', invoke, dict(env, KEXE_STRUCTURED_REPORT='1', KEXE_ALLOW='-'), ok=False)
            assert b':wire 37 :refused :not-granted' in denied.stdout + denied.stderr
            assert b'PFRAME ' not in denied.stdout
        if name in ('local-html-navigation', 'local-html-loop'):
            lines = [line[5:] for line in result.stdout.splitlines() if line.startswith(b'PAGE ')]
            assert len(lines) == 1 and isinstance(json.loads(lines[0]), list)
            (out / 'wire.json').write_bytes(lines[0] + b'\n')
            denied = run(name + '-denied', invoke, dict(env, KEXE_STRUCTURED_REPORT='1', KEXE_ALLOW='-'), ok=False)
            assert b':wire 37 :refused :not-granted' in denied.stdout + denied.stderr
            assert not any(line.startswith(b'PAGE ') for line in denied.stdout.splitlines())
        if name == 'local-loop-input':
            lines = [line[6:] for line in result.stdout.splitlines() if line.startswith(b'WIRE2 ')]
            assert len(lines) == 1 and isinstance(json.loads(lines[0]), list)
            (out / 'wire.json').write_bytes(lines[0])
        case_result = dict(name=name, verdict=0, artifact=pin(artifact), executable=pin(image),
                           lock=pin(lock), sources={str(path): pin(path) for path in files})
        if name == 'local-loop-input':
            case_result.update(eventWire=pin(a.event_wire), outputWire=pin(out / 'wire.json'))
        elif name == 'local-snapshot-read':
            case_result.update(inputSnapshot=pin(a.event_wire))
        elif name == 'local-snapshot-wire':
            case_result.update(outputSnapshot=pin(out / 'snapshot.bss1'))
        elif name == 'local-html-frame':
            case_result.update(outputFrame=pin(out / 'pageframe.bin'))
        elif name in ('local-dom-wire', 'local-html-navigation', 'local-html-loop'):
            case_result.update(outputWire=pin(out / 'wire.json'))
        cases.append(case_result)
        (out / 'shared-native.json').write_text(json.dumps(dict(status='PASS_SHARED_COMPONENTS_NATIVE',
            target=a.target, host=[platform.system(), machine, platform.release()], compiler=pin(compiler),
            cases=cases, complete=len(cases) == 16, emulation=False, kernelDesktopQualified=False,
            cssomQualified=False, itonamiDappsQualified=False, restorationQualified=False), indent=2) + '\n')
    print('PASS_SHARED_COMPONENTS_NATIVE', a.target, len(cases), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--compiler', type=Path, required=True)
    p.add_argument('--target', choices=['arm64', 'x86_64'], required=True)
    p.add_argument('--runner', type=Path)
    p.add_argument('--runner-source', type=Path)
    p.add_argument('--browser', type=Path, required=True)
    p.add_argument('--dom', type=Path, required=True)
    p.add_argument('--text', type=Path, required=True)
    p.add_argument('--html', type=Path, help='pinned kotoba.htmldom owner for HTML navigation')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--case', choices=['text', 'dom', 'text-edit', 'chrome', 'origin', 'storage', 'local-session', 'local-snapshot', 'local-snapshot-codec', 'local-snapshot-codec-limit', 'local-snapshot-codec-boundary', 'local-snapshot-wire', 'local-snapshot-read', 'local-view', 'local-text-view', 'local-multitab-text', 'local-dom-replay', 'local-dom-commit', 'local-controller', 'local-dom-events', 'local-dom-wire', 'local-loop', 'local-loop-input', 'local-native-actor', 'local-html-navigation', 'local-html-loop', 'local-html-frame', 'local-html-actor'])
    p.add_argument('--event-wire', type=Path, help='Chrome-emitted event for local-loop-input')
    a = p.parse_args()
    assert a.runner_source if a.target == 'arm64' else a.runner
    main(a)
