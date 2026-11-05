"""Validate the checked-in APK and metadata before publishing a release."""
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
metadata = json.loads((ROOT / 'releases/latest.json').read_text(encoding='utf-8'))
version = re.search(r"versionName\s+'([^']+)'", (ROOT / 'app/build.gradle').read_text(encoding='utf-8')).group(1)
assert metadata['tag'] == 'v' + version, 'Release tag does not match application version'
assert re.fullmatch(r'v[0-9]+\.[0-9]+(?:\.[0-9]+)?(?:-[a-zA-Z0-9.-]+)?', metadata['tag']), 'Invalid tag'
assert type(metadata['prerelease']) is bool
for key in ('title', 'apk', 'checksums', 'notes'):
    assert metadata[key] and '\n' not in metadata[key] and '\r' not in metadata[key]
for key in ('apk', 'checksums', 'notes'):
    path = (ROOT / metadata[key]).resolve()
    assert path.is_relative_to(ROOT) and path.is_file(), f'Invalid {key} path'
apk = ROOT / metadata['apk']
assert apk.name.endswith('-' + version.split('-')[0] + '.apk'), 'APK name does not match application version'
checksum_line = (ROOT / metadata['checksums']).read_text(encoding='ascii').strip().split()
assert len(checksum_line) == 2 and checksum_line[1] == apk.name, 'Unexpected checksum manifest'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == checksum_line[0], 'APK checksum mismatch'
assert (ROOT / metadata['notes']).stat().st_size > 0, 'Empty release notes'
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for key in ('tag', 'title', 'apk', 'checksums', 'notes', 'prerelease'):
            value = str(metadata[key]).lower() if key == 'prerelease' else metadata[key]
            output.write(f'{key}={value}\n')
print(f"PASS: {metadata['tag']}, APK SHA256 {checksum_line[0]}")
