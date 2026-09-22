"""校验发布版本并从中英文更新日志提取发布正文。"""

import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

CODENAMES = {'0.4': ('含章', 'Hanzhang')}


def revision(xml):
    return ET.fromstring(xml).findtext(
        '{http://maven.apache.org/POM/4.0.0}properties/'
        '{http://maven.apache.org/POM/4.0.0}revision')


def section(text, version):
    pattern = rf'^## \[V{re.escape(version)}\] - \d{{4}}-\d{{2}}-\d{{2}}\s*$'
    matches = list(re.finditer(pattern, text, re.M))
    if len(matches) != 1:
        raise ValueError('Expected exactly one changelog entry for ' + version)
    body = re.split(r'^## ', text[matches[0].end():], maxsplit=1, flags=re.M)[0].strip()
    if not body:
        raise ValueError('Empty release notes')
    return body


def prepare(root, previous, forced_version=None):
    version = revision((root / 'lingframe-dependencies/pom.xml').read_text(encoding='utf-8-sig'))
    if forced_version and version != forced_version:
        raise ValueError('Requested release version does not match the checked out source')
    if not forced_version and version == revision(previous):
        return None
    if not re.fullmatch(r'\d+\.\d+\.\d+', version or ''):
        raise ValueError('A release requires a stable semantic version')
    if revision((root / 'lingframe-bom/pom.xml').read_text(encoding='utf-8-sig')) != version:
        raise ValueError('BOM and dependencies versions differ')
    notes = [section((root / name).read_text(encoding='utf-8-sig'), version)
             for name in ('CHANGELOG.md', 'CHANGELOG.en.md')]
    codename = CODENAMES.get('.'.join(version.split('.')[:2]), ('', ''))
    return version, codename, '\n\n---\n\n'.join(notes) + '\n'


if __name__ == '__main__':
    previous = subprocess.check_output(
        ['git', 'show', 'HEAD^:lingframe-dependencies/pom.xml'])
    result = prepare(Path('.'), previous, os.environ.get('RELEASE_VERSION') or None)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write('release=' + str(result is not None).lower() + '\n')
        if result:
            version, codename, notes = result
            output.write('tag=V' + version + '\n')
            output.write('codename=' + codename[0] + '\n')
            output.write('release_branch=v' + version + '-release\n')
            Path('release-notes.md').write_text(notes, encoding='utf-8')
