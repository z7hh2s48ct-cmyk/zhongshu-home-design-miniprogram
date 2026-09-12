"""Read-only source/archive/deployment integrity checks; writes evidence beside this script."""
from pathlib import Path
import hashlib
import json
import tarfile
import xml.etree.ElementTree as ET
import zipfile

report = Path(__file__).resolve().parent
root = report.parent.parent
backup = root.parent / '.cleanup-backups/cleanup-20260909-01/source-before.tar'
baseline = json.loads((report / 'inventory.json').read_text(encoding='utf-8'))
after = json.loads((report / '清理后/inventory.json').read_text(encoding='utf-8'))
sha = lambda data: hashlib.sha256(data).hexdigest()

def xml_shape(data):
    def node(element):
        return [element.tag, dict(element.attrib), (element.text or '').strip(), [node(c) for c in element]]
    return node(ET.fromstring(data))

with tarfile.open(backup) as archive:
    members = {m.name.removeprefix('./'): m for m in archive.getmembers() if m.isfile()}
    migrations = []
    for name, member in members.items():
        if '/src/main/resources/db/migration/' in name and name.endswith('.sql'):
            original = archive.extractfile(member).read()
            current = root / name
            migrations.append({'path': name, 'sha256': sha(original), 'unchanged': current.exists() and current.read_bytes() == original})
    poms = []
    for name in baseline['activePoms']:
        poms.append({'path': name, 'sameActiveXml': xml_shape(archive.extractfile(members[name]).read()) == xml_shape((root / name).read_bytes())})

jar = root / '后端程序/yudao-server/target/yudao-server.jar'
with zipfile.ZipFile(jar) as deployment:
    entries = deployment.namelist()
    libs = sorted(n.split('/')[-1] for n in entries if n.startswith('BOOT-INF/lib/yudao-module-'))
    default_absent = not any(n.endswith('/DefaultController.class') for n in entries)

expected = {'identity', 'design', 'commerce', 'ai-orchestration', 'system', 'infra'}
library_modules = {n.removeprefix('yudao-module-').removesuffix('-2026.08-SNAPSHOT.jar') for n in libs}
assert all(m['unchanged'] for m in migrations), 'Historical migration drift'
assert all(p['sameActiveXml'] for p in poms), 'Active POM contract drift'
assert default_absent and library_modules == expected, 'Deployment contains unexpected modules/controller'
assert after['inactiveTotals']['modules'] == 0
new_migrations = [str(p.relative_to(root)).replace('\\', '/') for p in (root / '后端程序').rglob('*.sql')
                  if '/src/main/resources/db/migration/' in str(p).replace('\\', '/')
                  and str(p.relative_to(root)).replace('\\', '/') not in members]
result = {'backup': str(backup), 'backupSha256': sha(backup.read_bytes()), 'historicalMigrations': migrations,
          'newMigrations': new_migrations, 'activePomComparison': poms, 'runtimeBusinessLibraries': libs,
          'defaultControllerAbsent': default_absent, 'jarSha256': sha(jar.read_bytes()), 'jarBytes': jar.stat().st_size,
          'activePomCount': len(poms), 'removedInactiveJavaFiles': baseline['inactiveTotals']['javaFiles'],
          'removedInactiveJavaLines': baseline['inactiveTotals']['javaLines']}
(report / '清理完整性验证.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'historicalMigrationsUnchanged': len(migrations), 'newMigrations': new_migrations,
                  'sameActivePoms': len(poms), 'runtimeLibraries': libs, 'defaultControllerAbsent': default_absent}, ensure_ascii=False, indent=2))
