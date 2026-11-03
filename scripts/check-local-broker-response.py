#!/usr/bin/env python3
"""Offline canonical-broker / HTML-response qualification using CDP interception."""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
BROKER_SHA = '7ce9367be68652b30fb15eb3eb385968792774006bb3d67d56646803e2bb36cc'
spec = importlib.util.spec_from_file_location('native_live', ROOT / 'scripts/check-local-native-live.py')
live = importlib.util.module_from_spec(spec); spec.loader.exec_module(live)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--broker', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    broker = args.broker.resolve(strict=True); data = broker.read_bytes()
    assert hashlib.sha256(data).hexdigest() == BROKER_SHA, 'canonical broker pin mismatch'
    stage = output / 'app'; stage.mkdir()
    sources = [ROOT / 'test/browser-native/local_broker_response.html', ROOT / 'test/browser-native/local_broker_response_test.js', ROOT / 'src/browser/local_html_response.js']
    for path in sources: shutil.copyfile(path, stage / path.name)
    (stage / 'canonical-client-runtime.js').write_bytes(data)
    requests = []; mode = 'none'; body = '<p>承認済み fixture</p>'
    with tempfile.TemporaryDirectory(prefix='profile-', dir=output) as profile:
        pipe = live.Pipe(str(args.browser.resolve(strict=True)), profile)
        try:
            version = pipe.call('Browser.getVersion')['product']
            target = pipe.call('Target.createTarget', {'url':'about:blank'})['targetId']
            session = pipe.call('Target.attachToTarget', {'targetId':target, 'flatten':True})['sessionId']
            pipe.call('Fetch.enable', {'patterns':[{'urlPattern':'https://*', 'requestStage':'Request'}]}, session)
            def pump():
                while pipe.events:
                    event = pipe.events.pop(0)
                    if event.get('method') != 'Fetch.requestPaused': continue
                    params = event['params']; request = params['request']; owner = event.get('sessionId')
                    requests.append({'mode':mode, 'url':request['url'], 'method':request['method'],
                                     'headers': sorted(k.lower() for k in request.get('headers', {}))})
                    if mode == 'allow' and owner == session and request['url'] == 'https://service.invalid/page' and request['method'] == 'GET':
                        pipe.send('Fetch.fulfillRequest', {'requestId':params['requestId'], 'responseCode':200,
                            'body':base64.b64encode(body.encode()).decode(),
                            'responseHeaders':[{'name':'Content-Type', 'value':'text/html; charset=utf-8'},
                                               {'name':'Access-Control-Allow-Origin', 'value':'*'}]}, owner)
                    else:
                        pipe.send('Fetch.failRequest', {'requestId':params['requestId'], 'errorReason':'BlockedByClient'}, owner)
            def evaluate(expression):
                result = pipe.call('Runtime.evaluate', {'expression':expression, 'returnByValue':True}, session)
                pump()
                if 'exceptionDetails' in result: raise RuntimeError(result['exceptionDetails'])
                return result['result'].get('value')
            def wait(predicate):
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    state = evaluate('window.brokerFixture?.state() ?? null')
                    if state and predicate(state): return state
                    time.sleep(.05)
                raise TimeoutError(('broker fixture', state))
            def click(index):
                pos = evaluate(f'''(()=>{{const e=document.querySelectorAll('dialog.kotoba-client-consent button')[{index}];const r=e.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]}})()''')
                for kind in ('mousePressed','mouseReleased'):
                    pipe.call('Input.dispatchMouseEvent', {'type':kind, 'x':pos[0], 'y':pos[1], 'button':'left', 'clickCount':1}, session)
                    pump()
            pipe.call('Page.navigate', {'url':(stage / sources[0].name).as_uri()}, session)
            wait(lambda s:s['status']=='ready')
            evaluate("window.brokerFixture.start('/other')")
            rejected = wait(lambda s:s['status']=='error')
            assert not rejected['open'] and not requests and 'declared' in rejected['message']
            mode = 'deny'; evaluate("window.brokerFixture.start('/page')")
            denied = wait(lambda s:s['open']); assert 'https://service.invalid' in denied['scope'] and 'GET /page' in denied['scope']
            click(0); denied = wait(lambda s:s['status']=='error')
            assert 'not approved' in denied['message'] and not requests
            time.sleep(.25)
            mode = 'allow'; evaluate("window.brokerFixture.start('/page')"); wait(lambda s:s['open'])
            evaluate("document.querySelectorAll('dialog.kotoba-client-consent button')[1].click()")
            state = evaluate('window.brokerFixture.state()'); assert state['open'] and state['status']=='pending' and not requests
            click(1); accepted = wait(lambda s:s['status'] in ('ok','error'))
            assert accepted['status']=='ok', accepted
            assert len(requests)==1 and requests[0]['url']=='https://service.invalid/page' and requests[0]['method']=='GET'
            assert not any(x in requests[0]['headers'] for x in ('authorization','cookie','referer'))
            length, payload = accepted['encoded'].split('\n', 1)
            assert int(length)==len(payload.encode()) and payload == f'H1|4|1|tab-1|{len("https://service.invalid/page".encode())}|https://service.invalid/page' + body
            time.sleep(.25)
            mode = 'deny-again'; evaluate("window.brokerFixture.start('/page')"); wait(lambda s:s['open'])
            click(0); wait(lambda s:s['status']=='error'); assert len(requests)==1
        finally: pipe.close()
    receipt = {'status':'PASS_INTERCEPTED_CANONICAL_BROKER_RESPONSE', 'browser':version, 'brokerSha256':BROKER_SHA,
               'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, ROOT / 'scripts/check-local-native-live.py', Path(__file__).resolve()]},
               'requests':requests, 'realNetwork':'none: CDP intercepted all HTTPS requests and fulfilled locally',
               'limitations':['fixed test endpoint', 'CDP-generated trusted clicks are not human consent', 'broker independent review incomplete', 'no live service or production integration']}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(receipt['status'], version)


if __name__ == '__main__': main()
