"""Resolve locked build dependencies. This is never run by the Android app.

The first build needs network access, or --cache with the three verified files.
The resulting APK carries everything necessary for speech without downloads.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def resolve(item, destination, cache):
    file = destination / item['name']
    if file.is_file() and digest(file) == item['sha256']:
        return file
    with tempfile.NamedTemporaryFile(dir=destination, suffix='.part', delete=False) as out:
        temporary = Path(out.name)
        try:
            candidate = cache / item['name'] if cache else None
            if candidate and candidate.is_file():
                with candidate.open('rb') as source:
                    shutil.copyfileobj(source, out)
            else:
                request = urllib.request.Request(item['url'], headers={'User-Agent': 'Xiaohe-build'})
                with urllib.request.urlopen(request, timeout=60) as source:
                    shutil.copyfileobj(source, out)
            out.flush()
        except BaseException:
            out.close()
            temporary.unlink(missing_ok=True)
            raise
    if digest(temporary) != item['sha256']:
        temporary.unlink()
        raise ValueError('Dependency checksum mismatch: ' + item['name'])
    temporary.replace(file)
    return file


def prepare(project, cache=None):
    lock = json.loads((project / 'asr-dependencies.json').read_text(encoding='utf-8'))
    libraries = project / 'build' / 'thirdparty'
    assets = project / 'build' / 'generated' / 'asr-assets' / 'asr' / 'zh-14m'
    libraries.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    sdk, kotlin, model = [resolve(lock[key], libraries, cache) for key in ('sdk', 'kotlin', 'model')]
    with zipfile.ZipFile(sdk) as archive:
        (libraries / 'classes.jar').write_bytes(archive.read('classes.jar'))
        for info in archive.infolist():
            parts = info.filename.split('/')
            if len(parts) == 3 and parts[0] == 'jni' and parts[1] in lock['abis'] and parts[2].endswith('.so'):
                output = libraries / 'jni' / parts[1] / parts[2]
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(archive.read(info))
    found = set()
    with tarfile.open(model, 'r:bz2') as archive:
        for member in archive:
            name = Path(member.name).name
            if name not in lock['model']['files'] or not member.isfile():
                continue
            if name in found or member.size > 100 * 1024 * 1024:
                raise ValueError('Unexpected model archive member: ' + name)
            with archive.extractfile(member) as source, (assets / name).open('wb') as target:
                shutil.copyfileobj(source, target)
            found.add(name)
    if found != set(lock['model']['files']):
        raise ValueError('The model archive is missing required files')
    result = {'sdk': lock['sdk']['sha256'], 'kotlin': lock['kotlin']['sha256'],
              'model': lock['model']['sha256'], 'abis': lock['abis'],
              'assets': [{'name': name, 'bytes': (assets / name).stat().st_size,
                          'sha256': digest(assets / name)} for name in sorted(found)]}
    (libraries / 'verified.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('OFFLINE_ASR_DEPENDENCIES_READY ' + str(sum(x['bytes'] for x in result['assets'])))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path)
    arguments = parser.parse_args()
    prepare(Path(__file__).resolve().parent, arguments.cache)
