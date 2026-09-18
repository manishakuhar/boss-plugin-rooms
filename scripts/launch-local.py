#!/usr/bin/env python3
"""Launch the already-built native demo and a persistent loopback-only database."""
import json, os, pathlib, subprocess, time, urllib.request
root = pathlib.Path(__file__).resolve().parents[1]
data = root / '.local-rooms'
data.mkdir(mode=0o700, exist_ok=True)
logdir = pathlib.Path(os.environ.get('ROOMS_DEMO_LOG_DIR', str(data / 'logs')))
logdir.mkdir(parents=True, exist_ok=True)
def healthy():
    try:
        config = json.loads((data / 'connection.json').read_text())
        if not config['url'].startswith('http://127.0.0.1:'): return False
        with urllib.request.urlopen(config['url'] + '/health', timeout=1) as response:
            return json.load(response).get('mode') == 'local-test'
    except Exception: return False
if not healthy():
    with (logdir / 'database.log').open('a') as log:
        backend = subprocess.Popen(['node', str(root / 'backend/local-server.mjs')], cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(100):
        if healthy(): break
        if backend.poll() is not None: raise SystemExit('Database could not start. See ' + str(logdir / 'database.log'))
        time.sleep(.1)
    else: raise SystemExit('Database startup timed out.')
java = (root / 'build/local-demo-java.txt').read_text().strip()
classpath = (root / 'build/local-demo-classpath.txt').read_text()
with (logdir / 'native.log').open('a') as log:
    app = subprocess.Popen([java, '-Drooms.demo.connection=' + str(data / 'connection.json'), '-cp', classpath, 'ai.rever.boss.plugin.dynamic.rooms.localtest.LocalDemoKt'], cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
for _ in range(30):
    if app.poll() is not None:
        raise SystemExit('Trial app exited. See ' + str(logdir / 'native.log'))
    if os.name != 'posix' or __import__('sys').platform != 'darwin':
        print('Local demo started, PID:', app.pid)
        break
    result = subprocess.run(['osascript', '-e', 'tell application "System Events"', '-e', f'set trialProcess to first application process whose unix id is {app.pid}', '-e', 'set frontmost of trialProcess to true', '-e', 'get name of every window of trialProcess', '-e', 'end tell'], capture_output=True, text=True)
    if 'Rooms Local Test' in result.stdout:
        print('Verified visible trial windows:', result.stdout.strip())
        break
    time.sleep(.5)
else:
    raise SystemExit('Trial process started, but visible windows could not be verified. See native.log.')
