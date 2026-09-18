"""Check that the production JAR contains neither the local fixture nor bundled SDK classes."""
import json
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
with ZipFile(root / 'build/libs/boss-plugin-rooms-0.1.0.jar') as archive:
    names = archive.namelist()
    assert not any('/localtest/' in name or 'connection.json' in name or '.local-rooms' in name for name in names)
    assert not any(name.startswith('ai/rever/boss/plugin/api/') for name in names)
    manifest = json.loads(archive.read('META-INF/boss-plugin/plugin.json'))
    assert manifest['pluginId'] == 'ai.rever.boss.plugin.dynamic.rooms'
    assert manifest['mainClass'].replace('.', '/') + '.class' in names
    assert any(d['pluginId'] == 'ai.rever.boss.plugin.dynamic.aigateway' and d['optional'] for d in manifest['dependencies'])
print('PASS: production entry point, optional gateway dependency, and fixture/SDK separation')
