#!/usr/bin/env python3
"""Test IndexedDB recovery after killing Chrome during and after a transaction."""
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
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    requests = []
    with tempfile.TemporaryDirectory(prefix='session-crash-', dir=out) as profile:
        def launch():
            browser = live.Pipe(str(args.browser.resolve(strict=True)), profile)
            version = browser.call('Browser.getVersion')['product']
            target = browser.call('Target.createTarget', {'url':'about:blank'})['targetId']
            session = browser.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
            browser.call('Fetch.enable', {'patterns':[{'urlPattern':'http*','requestStage':'Request'}]}, session)
            def events():
                result = list(browser.events); browser.events.clear()
                for event in result:
                    if event.get('method') == 'Fetch.requestPaused':
                        requests.append(event['params']['request']['url'])
                        browser.send('Fetch.failRequest', {'requestId':event['params']['requestId'], 'errorReason':'BlockedByClient'}, event.get('sessionId'))
                return result
            def evaluate(expr, promise=False):
                result = browser.call('Runtime.evaluate', {'expression':expr, 'awaitPromise':promise, 'returnByValue':True}, session)
                events()
                if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
            browser.call('Page.navigate', {'url':(ROOT / 'test/browser-native/local_session_crash.html').as_uri()}, session)
            deadline = time.monotonic() + 20
            while not evaluate('typeof window.crashFixture === "object"'):
                if time.monotonic() > deadline: raise TimeoutError('fixture load')
                time.sleep(.05)
            return browser, session, version, evaluate, events
        browser, session, version, evaluate, events = launch()
        try:
            original = evaluate('window.crashFixture.save("BSS1|old", null)', True)
            browser.call('Debugger.enable', {}, session)
            evaluate('window.crashFixture.interruptSave("BSS1|new", ' + json.dumps(original) + ')')
            deadline = time.monotonic() + 20
            paused = None
            while time.monotonic() < deadline:
                browser.call('Browser.getVersion')
                for event in events():
                    if event.get('method') == 'Debugger.paused': paused = event
                if paused: break
                time.sleep(.05)
            if not paused: raise TimeoutError('transaction did not pause')
            assert paused['params']['callFrames'][0]['functionName'] == 'IDBObjectStore.put', paused
            assert paused['params']['callFrames'][1]['functionName'] == 'count.onsuccess', paused
            browser.proc.kill()
        finally: browser.close()
        browser, session, version2, evaluate, events = launch()
        try:
            after_interruption = evaluate('window.crashFixture.load()', True)
            assert after_interruption == original, (original, after_interruption)
            committed = evaluate('window.crashFixture.save("BSS1|new", ' + json.dumps(after_interruption) + ')', True)
            assert committed['revision'] == 2 and committed['payload'] == 'BSS1|new'
            browser.proc.kill()
        finally: browser.close()
        browser, session, version3, evaluate, events = launch()
        try:
            after_commit = evaluate('window.crashFixture.load()', True)
            assert after_commit == committed, (committed, after_commit)
        finally: browser.close()
    assert version == version2 == version3 and not requests, requests
    names = ['scripts/check-local-session-crash.py','scripts/check-local-native-live.py',
             'test/browser-native/local_session_crash.html','test/browser-native/local_session_crash_test.js',
             'src/browser/local_session_store.js']
    receipt = {'status':'PASS_LOCAL_SESSION_CRASH_RECOVERY','browser':version,
               'interruptedRevision':after_interruption['revision'],'committedRevision':after_commit['revision'],
               'sources':{name:hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names},
               'limitations':['Chrome process kill, not power loss or filesystem corruption',
                              'one deterministic point before IndexedDB transaction commit',
                              'test fixture payloads, no native actor or production app integration',
                              'checksums do not authenticate or protect against rollback']}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version)

if __name__ == '__main__': main()
