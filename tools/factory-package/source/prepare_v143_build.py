"""保存旧构建源码，只更新发布版本及短信载荷合同。"""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[3]
factory = Path(__file__).parent
evidence = root / 'research/d31/analysis/publish-v143-20260909/before-build-sources'
evidence.mkdir(exist_ok=False)
names = ['build_factory_package.py', 'build_factory_package.ps1', 'verify_factory_package.py',
         'audit_deliverable.py', 'test_release_contract.py', 'native/update_binary.c']
for name in names:
    path = factory / name
    backup = evidence / name
    backup.parent.mkdir(exist_ok=True, parents=True)
    shutil.copy2(path, backup)
    value = path.read_text(encoding='utf-8')
    assert '1.4.2' in value, name
    value = value.replace('1.4.2', '1.4.3').replace('v142-final-zip-audit-', 'v143-final-zip-audit-')
    value = value.replace('D31-Messages-0.3.1', 'D31-Messages-0.4.0')
    if name == 'verify_factory_package.py':
        old = '"4",\n                "0.3.1-dev-debug"'
        assert old in value
        value = value.replace(old, '"7",\n                "0.4.0-dev-debug"')
    path.write_text(value, encoding='utf-8', newline='\n')
tool = root / 'research/d31_flash_tool'
for relative in ['src/FirmwareManager.cs', 'build.ps1']:
    path = tool / relative
    shutil.copy2(path, evidence / path.name)
    value = path.read_text(encoding='utf-8').replace('1.4.2', '1.4.3').replace('1.6.5', '1.6.6')
    path.write_text(value, encoding='utf-8', newline='\n')
new_build = tool / 'build-v1.6.6.ps1'
assert not new_build.exists()
new_build.write_text((tool / 'build-v1.6.5.ps1').read_text(encoding='utf-8').replace('1.6.5', '1.6.6').replace('1.4.2', '1.4.3'), encoding='utf-8', newline='\n')
print('版本合同已更新：固件1.4.3、工具1.6.6、短信0.4.0。')
