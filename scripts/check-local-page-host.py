#!/usr/bin/env python3
"""Exercise the offline page projection host in Chrome; no page network."""
import argparse
import hashlib
import importlib.util
import json
import shutil
from pathlib import Path
import sys
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--wire', type=Path, help='fresh native-generated JSON operation batches')
    parser.add_argument('--frame', type=Path, help='fresh native-generated page-bearing actor frame')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = root / 'test/browser-native/local_page_host.html'
    sources = [fixture, root / 'test/browser-native/local_page_host_test.js',
               root / 'test/browser-native/page_fixture.js', root / 'test/browser-native/page_frame_fixture.js', root / 'src/browser/local_page_host.js',
               root / 'src/browser/local_dom_host.js', root / 'src/browser/local_actor_host.js', root / 'src/browser/local_worker_actor.js',
               root / 'src/browser/local_html_response.js']
    wire_sha = frame_sha = None
    if args.wire or args.frame:
        stage = output / 'app'
        for source in sources:
            destination = stage / source.relative_to(root)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        fixture = stage / fixture.relative_to(root)
        if args.wire:
            raw = args.wire.read_bytes()
            if len(raw) > 1048576 or not isinstance(json.loads(raw), list):
                raise ValueError('invalid native page wire')
            wire_sha = hashlib.sha256(raw).hexdigest()
            (fixture.parent / 'page_fixture.js').write_text('export default JSON.parse(' + json.dumps(raw.decode('utf-8')) + ');\n')
        if args.frame:
            raw_frame = args.frame.read_bytes()
            if len(raw_frame) > 1048641 or not raw_frame.startswith(b'PFRAME '):
                raise ValueError('invalid native page frame')
            frame_sha = hashlib.sha256(raw_frame).hexdigest()
            (fixture.parent / 'page_frame_fixture.js').write_text('export default ' + json.dumps(list(raw_frame)) + ';\n')
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('native_live', root / 'scripts/check-local-native-live.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
        browser = module.Pipe(str(args.browser.resolve(strict=True)), profile)
        try:
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url': 'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId': target, 'flatten': True})['sessionId']
            browser.call('Page.navigate', {'url': fixture.as_uri()}, session)
            deadline = time.monotonic() + 30
            while True:
                response = browser.call('Runtime.evaluate', {'expression': 'document.getElementById("result")?.textContent ?? "WAIT"', 'returnByValue': True}, session)
                value = response['result'].get('value')
                if value != 'WAIT' or time.monotonic() >= deadline:
                    break
                time.sleep(.1)
            html = browser.call('Runtime.evaluate', {'expression': 'document.documentElement.outerHTML', 'returnByValue': True}, session)['result'].get('value', '')
            (output / 'rendered.html').write_text(html)
            assert value == 'PASS', value
        finally:
            browser.close()
    receipt = {'status': 'PASS_OFFLINE_PAGE_HOST_FIXTURE', 'browser': version,
               'sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               'fixtureProvenance': 'supplied wire; verify native source lock separately' if args.wire else 'checked-in captured native output',
               'wireSha256': wire_sha, 'frameSha256': frame_sha,
               'notQualified': ['live parser integration', 'navigation', 'network consent', 'Kotoba layout', 'general HTML isolation']}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], receipt['browser'])


if __name__ == '__main__':
    main()
