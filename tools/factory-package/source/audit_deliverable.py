"""从最终签名ZIP提取全部交付载荷，再只读核对母机。"""
from pathlib import Path
import argparse, datetime, gzip, hashlib, json, re, shutil, subprocess, zipfile
from verify_oat import verify
from verify_factory_package import verify_app_page, expected_archive_hashes

ROOT=Path(__file__).resolve().parents[3]
TOOLS=Path(__file__).parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--package',type=Path,required=True)
args=parser.parse_args()
OUT=ROOT/'research/d31/analysis'/('v142-final-zip-audit-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
OUT.mkdir()
EXTRACTED=OUT/'extracted'
ADB=['C:/Dev/android-sdk/platform-tools/adb.exe','-P','5042','-s','192.168.2.62:5555']
records=[]
result={'结论':'未通过；复核尚未完成','未解释差异':[]}

def sha(path):
    with path.open('rb') as source:return hashlib.file_digest(source,'sha256').hexdigest()

def linux(path):return '/mnt/host/c'+path.resolve().as_posix()[2:]

def capture(label,command,timeout=120):
    stem=str(len(records)).zfill(3)+'-'+label
    print('核对：'+label,flush=True)
    started=datetime.datetime.now().isoformat()
    process=subprocess.run(command,capture_output=True,timeout=timeout)
    (OUT/(stem+'.stdout')).write_bytes(process.stdout)
    (OUT/(stem+'.stderr')).write_bytes(process.stderr)
    records.append({'命令':command,'开始':started,'结束':datetime.datetime.now().isoformat(),'退出码':process.returncode,'输出':stem+'.stdout','错误输出':stem+'.stderr'})
    (OUT/'commands-private.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    if process.returncode:raise RuntimeError('命令未通过：'+label)
    return process.stdout.decode('utf-8',errors='replace').replace('\r','')

def shell(label,command,timeout=120):
    raw=capture(label,ADB+['shell',command+'\nrc=$?; echo D31_AUDIT_RC=$rc'],timeout)
    body,marker,status=raw.rpartition('D31_AUDIT_RC=')
    if not marker or status.strip()!='0':raise RuntimeError('设备命令未通过：'+label)
    return body.strip()

def hashes(text,prefix):
    found={}
    for line in text.splitlines():
        if '  ' in line:
            digest,path=line.split('  ',1)
            if len(digest)==64 and path.startswith(prefix):found[path[len(prefix):]]=digest
    return found

def metadata(text,prefix):
    found={}
    for line in text.splitlines():
        fields=line.split('|',4)
        if len(fields)==5 and fields[-1].startswith(prefix):found[fields[-1][len(prefix):]]=fields[:3]
    return found

try:
    print('最终ZIP复核目录：'+str(OUT),flush=True)
    approved=json.loads((TOOLS/'approved-package.json').read_text())
    result['ZIP']={'文件':args.package.name,'字节数':args.package.stat().st_size,'SHA256':sha(args.package)}
    assert args.package.name==approved['fileName'] and args.package.stat().st_size==approved['bytes']
    assert result['ZIP']['SHA256']==approved['sha256'].lower()
    expected=expected_archive_hashes()
    with zipfile.ZipFile(args.package) as archive:
        assert len(archive.namelist())==len(set(archive.namelist())),'ZIP重复条目'
        assert set(n for n in archive.namelist() if n.startswith('payload/'))==set(expected)|{'payload/system.img.gz','payload/manifest.json'}
        for entry in archive.infolist():
            assert not Path(entry.filename).is_absolute() and '..' not in Path(entry.filename).parts
            print('解压最终交付：'+entry.filename,flush=True)
            archive.extract(entry,EXTRACTED)
        for entry,(size,digest) in expected.items():
            item=EXTRACTED/entry
            assert item.stat().st_size==size and sha(item)==digest.lower(),entry
    image=OUT/'system.img'
    with gzip.open(EXTRACTED/'payload/system.img.gz','rb') as source,image.open('wb') as target:shutil.copyfileobj(source,target,4*1024*1024)
    sources=json.loads((TOOLS/'sources-v1.4.2.json').read_text())
    assert [image.stat().st_size,sha(image).upper()]==sources['partitions/system.img']
    capture('镜像逐文件扫描',['wsl.exe','-d','docker-desktop','--','sh',linux(TOOLS/'audit_final_image.sh'),linux(image),linux(OUT)],300)
    assert shell('构建基线','getprop ro.build.fingerprint')=='alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys'
    live=hashes(shell('当前system完整哈希','find /system -type f -exec busybox sha256sum {} \\;',480),'/system/')
    packed=hashes((OUT/'system-hashes.txt').read_text(),'./')
    differences=sorted(k for k,v in packed.items() if k in live and live[k]!=v)
    assert set(differences)=={'vendor/3rd-app/nexui.apk','vendor/3rd-app/imscc.apk','vendor/3rd-app/getnumber.apk','vendor/starnet/launcher/config/config-tab'},differences
    assert not packed.keys()-live.keys()
    removed=sorted(live.keys()-packed.keys())
    forbidden=json.loads((TOOLS/'master_target_state.json').read_text(encoding='utf-8'))['必须物理删除的系统路径']
    assert all(any('/system/'+n==p or ('/system/'+n).startswith(p+'/') for p in forbidden) for n in removed)
    assert len(removed)==29
    live_meta=metadata(shell('当前system权限属主','find /system -exec stat -c "%a|%u|%g|%s|%n" {} \\;',360),'/system/')
    packed_meta=metadata((OUT/'system-metadata.txt').read_text(),'./')
    meta_diff=[{'路径':p,'包内':v,'母机':live_meta.get(p)} for p,v in packed_meta.items() if p in live_meta and v!=live_meta[p]]
    assert all(x['路径']=='bin/install-recovery.sh' and x['包内']==['755','0','0'] and x['母机']==['750','0','0'] for x in meta_diff),meta_diff
    raw_links=shell('当前system符号链接','find /system -type l -exec sh -c \'printf "%s|%s\\n" "$1" "$(readlink "$1")"\' sh {} \\;',180)
    live_links={line.replace('/system/','./',1) for line in raw_links.splitlines() if line}
    assert set((OUT/'system-links.txt').read_text().splitlines())==live_links,'符号链接差异'
    result.update({'system相同文件数':sum(live.get(k)==v for k,v in packed.items()),'system内容差异':differences,'已精简原厂文件':removed,'权限属主差异':meta_diff,'system镜像SHA256':sha(image)})
    patches=EXTRACTED/'payload/system-patches'
    temporary='/data/local/tmp/d31-final-zip-verify-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    shell('初始化检查临时目录','mkdir '+temporary)
    for name in ('startup-handover.jar','init-package-restrictions.xml','init-runtime-permissions.xml'):
        capture('临时校验文件-'+name,ADB+['push',str(patches/name),temporary+'/'+name])
        assert shell('临时文件回读-'+name,'busybox sha256sum '+temporary+'/'+name).split()[0]==sha(patches/name)
    for mode,expected_output in (('--inspect','FACTORY_INIT_INSPECT_ONLY'),('--verify','FACTORY_STATE_VERIFIED')):
        text=shell('真实安卓初始化'+mode,'CLASSPATH='+temporary+'/startup-handover.jar app_process /system/bin FactoryInit '+mode+' '+temporary,90)
        assert expected_output in text
    result['真实安卓初始化核对']='最终ZIP中的类与两份XML执行--inspect及--verify通过；未执行--apply'
    template=(OUT/'system-config-tab').read_bytes()
    assert template==(patches/'config-tab').read_bytes()
    verify_app_page(template)
    assert json.loads(shell('母机实际应用页','cat /data/starnet/launcher/config/config-tab'))==json.loads(template)
    with zipfile.ZipFile(OUT/'number-placeholder.apk') as archive:assert not any(n.endswith('.dex') for n in archive.namelist())
    manifest=capture('取号占位包权限',['C:/Dev/android-sdk/build-tools/34.0.0/aapt.exe','dump','xmltree',str(OUT/'number-placeholder.apk'),'AndroidManifest.xml'])
    assert 'uses-permission' not in manifest and 'hasCode' in manifest
    assert manifest.count('android:enabled(0x0101000e)=(type 0x12)0x0')==4
    installer=(TOOLS/'native/update_binary.c').read_text(encoding='utf-8')
    entries=re.findall(r'\{"(payload/[^\"]+)", "([^\"]+)", (0[0-7]+), (\d+), (\d+)\}',installer)
    report=[]
    for entry,destination,mode,uid,gid in entries:
        if entry.startswith('payload/runtime/'):continue
        name=Path(entry).name
        source=EXTRACTED/entry
        target=destination
        if entry.startswith('payload/apps/'):
            pkg=target.split('/')[3].rsplit('-',1)[0]
            value=shell('安装路径-'+pkg,'pm path '+pkg)
            assert value.startswith('package:'),pkg
            target=value[8:]
        actual=shell('载荷-'+name,'if [ -f '+target+' ]; then busybox sha256sum '+target+'; stat -c "%a|%u|%g|%s" '+target+'; else echo ABSENT; fi')
        digest=sha(source)
        match=actual.split()[0]==digest
        reason=''
        if not match:
            if name in ('init-package-restrictions.xml','init-runtime-permissions.xml','factory-init-required'):reason='新机初始化白名单，母机不执行清数据初始化；真实安卓只读验证另有记录'
            elif name=='startup-handover.jar':
                baseline=ROOT/'research/d31/analysis/2026-09-08-startup-handover/build-20260909-015536/handover.jar'
                assert actual.split()[0]==sha(baseline)
                reason='最终复核补入Zello及守护省电白名单初始化；母机两项已存在，新JAR真实安卓只读核对通过'
            elif name in ('startup-cellular-enabled','recovery-volume-enabled') and actual!='ABSENT':reason='开关只判断文件存在；内容不参与配置'
            elif name=='config-tab':
                assert actual=='ABSENT'
                reason='发行模板新增落盘副本；系统模板和母机实际应用页已核对相同'
            elif name=='rescue-start.sh':
                old=shell('母机探针启动入口','cat '+target)
                assert old.replace('/data/user/0/net.elfradio.d31bootstrap/files/rescue/enabled','/data/local/d31-rescue/enabled').strip()==source.read_text().strip()
                reason='仅将启动标记迁出应用私有目录，不依赖首次创建应用数据'
            elif name=='rescue-enabled':
                assert source.read_text().strip()=='enabled'
                generation=shell('探针代次标记','cat '+target)
                assert re.fullmatch(r'\d+-\d+',generation)
                reason='母机升级产生的代次标记与发行初始标记不同；守护仅比较当前代次是否变化'
            else:result['未解释差异'].append(name)
        report.append({'文件':entry,'部署路径':destination,'母机一致':match,'差异说明':reason,'SHA256':digest})
    oats=[]
    for entry,destination,*_ in entries:
        if entry.startswith('payload/runtime/'):
            apk_destination=destination.replace('/oat/arm64/base.odex','/base.apk')
            apk_entry=next(e for e,d,*_ in entries if d==apk_destination)
            oats.append(verify(EXTRACTED/entry,EXTRACTED/apk_entry,apk_destination))
    for part in ('boot','logo'):
        value=shell('当前分区-'+part,'busybox sha256sum /dev/block/platform/mtk-msdc.0/11230000.msdc0/by-name/'+part,60)
        assert value.split()[0]==sha(EXTRACTED/('payload/'+part+'.img')),part
    shell('设置与组件原始状态','settings list global; settings list secure; settings list system; cat /data/system/users/0/package-restrictions.xml; cat /data/system/users/0/runtime-permissions.xml; appops get com.starnet.getnumber SEND_SMS')
    shell('独立支持与恢复状态','cat /data/local/d31-system-support/guard.log; ls -l /dev/socket/d31-system-actions; ps | grep -E "d31-system|d31-rescue|d31system"')
    result.update({'载荷对照':report,'编译缓存校验':oats})
    assert not result['未解释差异'],result['未解释差异']
    result['结论']='最终ZIP逐文件复核通过；不代表全新用户分区实刷验收'
    print('FINAL_ZIP_FILE_AUDIT_PASS',result['system相同文件数'],len(report),len(oats),OUT,flush=True)
except BaseException as error:
    result['错误']=str(error)
    raise
finally:
    (OUT/'audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
