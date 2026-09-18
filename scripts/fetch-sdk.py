"""Fetch the immutable released SDK; fail closed if its bytes change."""
from pathlib import Path
import hashlib
import urllib.request
version = '1.0.93'
expected = '492494733e6140019d42694849a8e94f22d87a701289fc824de1fd852a9753ca'
target = Path(__file__).resolve().parents[1] / 'build/downloaded-deps/boss-plugin-api.jar'
if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
    print('SDK already verified.')
else:
    data = urllib.request.urlopen(f'https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v{version}/boss-plugin-api-{version}.jar', timeout=60).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise SystemExit('SDK checksum mismatch; nothing installed.')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print('Downloaded and verified SDK ' + version)
