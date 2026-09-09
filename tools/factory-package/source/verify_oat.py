"""验证Android 6 OAT的每个DEX与APK条目CRC及安装路径对应。"""
from pathlib import Path
import struct, zipfile, json, hashlib

def verify(oat, apk, destination):
    raw=oat.read_bytes()
    header=raw.find(b'oat\n064\x00')
    if header<0: raise ValueError('不支持的OAT版本 '+str(oat))
    u32=lambda p:struct.unpack_from('<I',raw,p)[0]
    count=u32(header+20)
    pos=header+72+u32(header+68)
    result=[]
    with zipfile.ZipFile(apk) as z:
        expected={n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex') and '/' not in n}
        found=set()
        for i in range(count):
            size=u32(pos); pos+=4
            if not 0<size<1024: raise ValueError('OAT目录长度异常')
            location=raw[pos:pos+size].decode(); pos+=size
            crc=u32(pos); dex_offset=u32(pos+4); pos+=8
            base,sep,entry=location.partition(':')
            entry=entry if sep else 'classes.dex'
            if base not in (destination,'base.apk'): raise ValueError('安装路径不匹配 '+location+' != '+destination)
            info=z.getinfo(entry)
            if info.CRC!=crc: raise ValueError('APK与OAT代码不匹配 '+entry)
            dex=header+dex_offset
            if raw[dex:dex+4]!=b'dex\n': raise ValueError('缺少内嵌DEX')
            dex_size=u32(dex+32)
            # ART会在内嵌DEX指令区做quickening，原始SHA-1及Adler32留在DEX头中。
            original=z.read(entry)
            if dex_size!=len(original) or raw[dex:dex+112]!=original[:112]:
                raise ValueError('DEX原始签名或结构不一致 '+entry)
            classes=u32(dex+96)
            pos+=classes*4
            found.add(entry)
            result.append({'条目':entry,'长度':dex_size,'CRC32':f'{crc:08x}'})
        if found!=expected: raise ValueError('DEX集合不完整')
    return {'缓存':oat.name,'APK':apk.name,'安装路径':destination,'DEX':result,'SHA256':hashlib.file_digest(oat.open('rb'),'sha256').hexdigest()}

if __name__=='__main__':
    source=Path(__file__).resolve().parents[1]/'analysis/2026-09-09-factory-v1.4.1'
    result=[]
    for item in json.loads((source/'oat-sources.json').read_text()):
        name=item['artifact'].removesuffix('-arm64.odex')
        result.append(verify(source/'runtime'/item['artifact'],source/'apks'/(name+'.apk'),item['path'].replace('/oat/arm64/base.odex','/base.apk')))
    (source/'oat-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('OAT与APK全部DEX的CRC32、原始签名及结构核验通过',len(result))
