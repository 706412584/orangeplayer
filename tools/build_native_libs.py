#!/usr/bin/env python3
"""抽出按需下载的 native so 并打包成 zip，打印 sha256/体积供 NativeLibManager 清单使用。

用法：
  python tools/build_native_libs.py                    # 本地：从 Gradle 缓存取源
  python tools/build_native_libs.py --fetch            # CI：直连官方源下载（不依赖 Gradle）
  python tools/build_native_libs.py --verify <file>    # 校验 NativeLibManager 里的常量与本地产物一致

数据来源（版本已 pin，与 APK 构建用的是同一批工件）：
  sherpa-onnx AAR  -> libonnxruntime / libsherpa-onnx-*
  tesseract4android AAR -> libtesseract / libleptonica / libjpeg / libpngx
  libtorrent4j-android-* jar -> libtorrent4j
  mlkit translate AAR -> libtranslate_jni
"""
import argparse, glob, hashlib, json, os, re, sys, urllib.request, zipfile

GC = os.path.expanduser('~/.gradle/caches')
FETCH_DIR = 'build/native-libs-src'

BUNDLES = {
    'torrent': ['libtorrent4j.so'],
    'asr': ['libonnxruntime.so', 'libsherpa-onnx-jni.so',
            'libsherpa-onnx-c-api.so', 'libsherpa-onnx-cxx-api.so'],
    'ocr': ['libtesseract.so', 'libleptonica.so', 'libjpeg.so', 'libpngx.so'],
    'translate': ['libtranslate_jni.so'],
}
ABIS = ['arm64-v8a', 'armeabi-v7a']

# 与 app/build.gradle 的依赖版本一致；升级依赖必须同步改这里与 NativeLibManager 常量
REMOTE = {
    'sherpa': 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar',
    'tess': 'https://repo1.maven.org/maven2/cz/adaptech/tesseract4android/'
            'tesseract4android/4.7.0/tesseract4android-4.7.0.aar',
    'mlk': 'https://dl.google.com/dl/android/maven2/com/google/mlkit/'
           'translate/17.0.2/translate-17.0.2.aar',
    'tr-arm64': 'https://repo1.maven.org/maven2/org/libtorrent4j/'
                'libtorrent4j-android-arm64/2.0.6-26/libtorrent4j-android-arm64-2.0.6-26.jar',
    'tr-arm': 'https://repo1.maven.org/maven2/org/libtorrent4j/'
              'libtorrent4j-android-arm/2.0.6-26/libtorrent4j-android-arm-2.0.6-26.jar',
}


def _glob1(pat, what):
    hits = glob.glob(pat)
    if not hits:
        sys.exit('未找到 %s：%s' % (what, pat))
    return sorted(hits, key=os.path.getmtime)[-1]


def _fetch(key):
    """下载源工件到 FETCH_DIR（CI 用；幂等）。"""
    os.makedirs(FETCH_DIR, exist_ok=True)
    dest = os.path.join(FETCH_DIR, key + '-' + os.path.basename(REMOTE[key]))
    if not os.path.exists(dest):
        print('下载 %s' % REMOTE[key])
        urllib.request.urlretrieve(REMOTE[key], dest + '.part')
        os.replace(dest + '.part', dest)
    return dest


def sources(fetch):
    if fetch:
        tr = {'arm64-v8a': _fetch('tr-arm64'), 'armeabi-v7a': _fetch('tr-arm')}
        # sherpa 的 so 在 AAR 里是 jni/<abi>/ 布局；这里解包到目录以复用 read_lib
        sherpa_dir = os.path.join(FETCH_DIR, 'sherpa')
        if not os.path.isdir(sherpa_dir):
            with zipfile.ZipFile(_fetch('sherpa')) as z:
                for n in z.namelist():
                    if n.startswith('jni/') and n.endswith('.so'):
                        z.extract(n, sherpa_dir)
        return sherpa_dir, _fetch('tess'), _fetch('mlk'), tr

    sherpa_dir = _glob1(GC + '/8.13/transforms/*/transformed/jetified-sherpa-onnx-1.13.7', 'sherpa AAR')
    tess = _glob1(GC + '/modules-2/files-2.1/cz.adaptech.tesseract4android/tesseract4android/4.7.0/*/tesseract4android-4.7.0.aar', 'tesseract AAR')
    mlk = _glob1(GC + '/modules-2/files-2.1/com.google.mlkit/translate/17.0.2/*/translate-17.0.2.aar', 'mlkit translate AAR')
    tr = {}
    for suffix, abi in (('arm64', 'arm64-v8a'), ('arm', 'armeabi-v7a')):
        tr[abi] = _glob1(GC + '/modules-2/files-2.1/org.libtorrent4j/libtorrent4j-android-%s/2.0.6-26/*/*.jar' % suffix,
                         'libtorrent4j-android-%s' % suffix)
    return sherpa_dir, tess, mlk, tr


def read_lib(name, abi, sherpa_dir, tess, mlk, tr):
    """返回 so 字节。"""
    p = os.path.join(sherpa_dir, 'jni', abi, name)
    if os.path.exists(p):
        return open(p, 'rb').read()
    if name == 'libtorrent4j.so':
        with zipfile.ZipFile(tr[abi]) as z:
            return z.read('lib/%s/%s' % (abi, name))
    for aar, member in ((tess, 'jni/%s/%s' % (abi, name)), (mlk, 'jni/%s/%s' % (abi, name))):
        with zipfile.ZipFile(aar) as z:
            try:
                return z.read(member)
            except KeyError:
                pass
    sys.exit('未找到 %s (%s)' % (name, abi))


def verify(manifest_path, java_path):
    """校验 NativeLibManager 的 size/sha256 常量与 manifest.json 一致，返回错误列表。"""
    with open(manifest_path) as f:
        manifest = json.load(f)
    src = open(java_path, encoding='utf-8').read()
    errors = []
    for bundle, by_abi in manifest.items():
        for abi, info in by_abi.items():
            # Java 里的 long 字面量带下划线分隔（如 4_489_193L），两种写法都接受
            for label, value in (('size', info['size']), ('rawSize', info['rawSize'])):
                plain = '%dL' % value
                grouped = '%sL' % format(value, '_d')
                if plain not in src and grouped not in src:
                    errors.append('%s/%s %s %d 未出现在 NativeLibManager'
                                  % (bundle, abi, label, value))
            if info['sha256'] not in src:
                errors.append('%s/%s sha256 %s 未出现在 NativeLibManager' % (bundle, abi, info['sha256']))
    return errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='build/native-libs')
    ap.add_argument('--fetch', action='store_true', help='直连官方源下载源工件（CI 用）')
    ap.add_argument('--verify', metavar='JAVA',
                    help='校验 NativeLibManager 常量与 --out/manifest.json 一致')
    args = ap.parse_args()

    if args.verify:
        errors = verify(os.path.join(args.out, 'manifest.json'), args.verify)
        for e in errors:
            print('MISMATCH: %s' % e)
        print('verify %s: %d 处不一致' % (args.verify, len(errors)))
        sys.exit(1 if errors else 0)

    sherpa_dir, tess, mlk, tr = sources(args.fetch)
    os.makedirs(args.out, exist_ok=True)
    manifest = {}
    for bundle, libs in BUNDLES.items():
        for abi in ABIS:
            fname = '%s-%s.zip' % (bundle, abi)
            path = os.path.join(args.out, fname)
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
                for lib in libs:
                    data = read_lib(lib, abi, sherpa_dir, tess, mlk, tr)
                    # 只存文件名（无目录），落位时直接解到 native-libs/<abi>/
                    zi = zipfile.ZipInfo(lib, date_time=(2024, 1, 1, 0, 0, 0))
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    zi.external_attr = 0o644 << 16
                    z.writestr(zi, data)
            raw = open(path, 'rb').read()
            with zipfile.ZipFile(path) as z:
                raw_size = sum(i.file_size for i in z.infolist())
            manifest.setdefault(bundle, {})[abi] = {
                'file': fname,
                'size': len(raw),
                'rawSize': raw_size,
                'sha256': hashlib.sha256(raw).hexdigest(),
            }
            print('%-9s %-12s %7.1f MiB  sha256=%s' % (
                bundle, abi, len(raw) / 1048576, manifest[bundle][abi]['sha256']))

    with open(os.path.join(args.out, 'manifest.json'), 'w') as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print('\nmanifest: %s' % os.path.join(args.out, 'manifest.json'))


if __name__ == '__main__':
    main()
