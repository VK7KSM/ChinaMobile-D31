"""对正式原像清单逐文件比较，只允许已批准六项覆盖层。"""
from pathlib import Path
import json
import subprocess
from derive_system_image import linux

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
candidate = json.loads((STAGE / 'system-image-candidate.json').read_text(encoding='utf-8'))
image = ROOT / candidate['image']
out = image.parent
baseline = ROOT / 'research/d31/analysis/v143-final-zip-audit-20260909-170903'
result = subprocess.run(['wsl.exe', '-d', 'docker-desktop', '--', 'sh', linux(STAGE / 'audit_system_tree.sh'), linux(image), linux(out)], capture_output=True, timeout=120)
(out / 'tree-audit.stdout.txt').write_bytes(result.stdout)
(out / 'tree-audit.stderr.txt').write_bytes(result.stderr)
assert result.returncode == 0, result.stderr
def hashes(path):
    entries = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        digest, name = line.split(None, 1)
        name = name.removeprefix('./')
        assert name not in entries
        entries[name] = digest.upper()
    return entries
old, new = hashes(baseline / 'system-hashes.txt'), hashes(out / 'system-hashes.txt')
added = sorted(new.keys() - old.keys())
missing = sorted(old.keys() - new.keys())
changed = sorted(name for name in old.keys() & new.keys() if old[name] != new[name])
overlay = json.loads((STAGE / 'system-overlay.json').read_text(encoding='utf-8'))
assert not missing and set(added + changed) == set(overlay), (added, missing, changed)
for name, entry in overlay.items():
    assert new[name] == entry['sha256']
def lines(path):
    return sorted(path.read_text(encoding='utf-8-sig').splitlines())
assert lines(baseline / 'system-links.txt') == lines(out / 'system-links.txt')
def metadata(path):
    return {parts[4].removeprefix('./'): parts[:4] for line in lines(path) if len(parts := line.split('|', 4)) == 5}
old_meta, new_meta = metadata(baseline / 'system-metadata.txt'), metadata(out / 'system-metadata.txt')
metadata_changes = {}
for name in old_meta.keys() & new_meta.keys():
    # 目录长度可随新增成员变化；权限和属主不能无计划变化。
    fields = 3 if name not in old else 4
    if old_meta[name][:fields] != new_meta[name][:fields]:
        metadata_changes[name] = dict(before=old_meta[name], after=new_meta[name])
assert set(metadata_changes) <= set(overlay), metadata_changes
report = dict(status='通过', baselineFiles=len(old), candidateFiles=len(new), added=added, changed=changed,
              missing=missing, unchanged=len(old)-len(changed), symlinks='全部逐条一致', metadataChanges=metadata_changes,
              boundary='仅离线派生镜像核验；未实刷，未验证system-only媒体')
(out / 'tree-comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
