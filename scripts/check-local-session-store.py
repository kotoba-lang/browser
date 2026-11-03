#!/usr/bin/env python3
"""Verify IndexedDB session-record persistence in two isolated Chrome runs."""
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
    p = argparse.ArgumentParser()
    p.add_argument('--browser', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--snapshot-file', type=Path, help='UTF-8 snapshot emitted by a native fixture')
    p.add_argument('--restored-file', type=Path, help='write the verified test payload for a native restore fixture')
    a = p.parse_args()
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    source = ROOT / 'test/browser-native/local_session_store.html'
    payload = a.snapshot_file.read_text(encoding='utf-8') if a.snapshot_file else '{:snapshot/version 1 :snapshot/tabs []}'
    requests = []
    with tempfile.TemporaryDirectory(prefix='profile-', dir=out) as profile:
        def launch():
            pipe = live.Pipe(str(a.browser.resolve(strict=True)), profile)
            version = pipe.call('Browser.getVersion')['product']
            target = pipe.call('Target.createTarget', {'url':'about:blank'})['targetId']
            session = pipe.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
            pipe.call('Fetch.enable', {'patterns':[{'urlPattern':'http*','requestStage':'Request'}]}, session)
            def evaluate(expr, promise=False):
                result = pipe.call('Runtime.evaluate', {'expression':expr, 'awaitPromise':promise, 'returnByValue':True}, session)
                while pipe.events:
                    event = pipe.events.pop(0)
                    if event.get('method') == 'Fetch.requestPaused':
                        requests.append(event['params']['request']['url'])
                        pipe.send('Fetch.failRequest', {'requestId':event['params']['requestId'], 'errorReason':'BlockedByClient'}, event.get('sessionId'))
                if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
            pipe.call('Page.navigate', {'url':source.as_uri() + '?profile=test-session'}, session)
            deadline = time.monotonic() + 20
            while not evaluate('typeof window.sessionFixture === "object"'):
                if time.monotonic() > deadline: raise TimeoutError('fixture load')
                time.sleep(.05)
            return pipe, evaluate, version
        pipe, evaluate, version = launch()
        try: expected = evaluate('window.sessionFixture.first(' + json.dumps(payload, ensure_ascii=True) + ')', True)
        finally: pipe.close()
        pipe, evaluate, second_version = launch()
        try: verified = evaluate('window.sessionFixture.afterRestart(' + json.dumps(expected, ensure_ascii=True) + ')', True)
        finally: pipe.close()
    assert version == second_version and verified == {'corrupted':True, 'conflict':True, 'undefinedRejected':True, 'missingWriteRejected':True, 'revision':2, 'payload':expected['payload']}
    assert not requests, requests
    if a.restored_file: a.restored_file.write_text(verified['payload'], encoding='utf-8')
    sources = ['src/browser/local_session_store.js','src/browser/local_session_persistence.js','test/browser-native/local_session_store.html',
               'test/browser-native/local_session_store_test.js','scripts/check-local-native-live.py','scripts/check-local-session-store.py']
    result = {'status':'PASS_LOCAL_SESSION_STORE_RESTART','browser':version,'recordRevision':expected['revision'], 'payloadSha256':hashlib.sha256(payload.encode()).hexdigest(),
              'nativeFixturePayload':bool(a.snapshot_file),
              'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources},
              'limitations':['test-only profile and payload', 'checksum detects corruption but is not authentication',
                             'persistence coordinator uses a synthetic actor; real actor restart is not qualified',
                             'no crash-during-write, eviction, cross-device or production qualification']}
    (out / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(result['status'], version)


if __name__ == '__main__': main()
