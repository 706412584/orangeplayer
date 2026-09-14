#!/usr/bin/env python3
"""抽出按需下载的 native so 并打包成 zip，打印 sha256/体积供 NativeLibManager 清单使用。

用法：
  python tools/build_native_libs.py                    # 本地：从 Gradle 缓存 / 仓库内产物取源
  python tools/build_native_libs.py --fetch            # CI：直连官方源下载（不依赖 Gradle）
  python tools/build_native_libs.py --verify <file>    # 校验 NativeLibManager 里的常量与本地产物一致
  python tools/build_native_libs.py --only ijk         # 只构建指定 bundle（逗号分隔）
  python tools/build_native_libs.py --skip ffmpeg      # 跳过指定 bundle

数据来源（版本已 pin，与 APK 构建用的是同一批工件）：
  sherpa-onnx AAR        -> libonnxruntime / libsherpa-onnx-*
  tesseract4android AAR  -> libtesseract / libleptonica / libjpeg / libpngx
  libtorrent4j-android-* jar -> libtorrent4j
  mlkit translate AAR    -> libtranslate_jni
  gsyVideoPlayer-ex_so   -> libijk*（仓库内 jniLibs，需 strip）
  AliyunPlayer AAR       -> libalivcffmpeg / libsaas*
  libmpv AAR             -> libmpv / libav* / libc++_shared / libplayer
  tools/prebuilt/ffmpeg  -> liborangeffmpegkit（CI 编不出，见下）
"""
import argparse, glob, hashlib, json, os, subprocess, sys, urllib.request, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GC = os.path.expanduser('~/.gradle/caches')
FETCH_DIR = 'build/native-libs-src'

# 每个 bundle 的 so 清单，按 DT_NEEDED 拓扑序（被依赖者在前），与 NativeLibManager 一致
BUNDLES = {
    'torrent': ['libtorrent4j.so'],
    'asr': ['libonnxruntime.so', 'libsherpa-onnx-jni.so',
            'libsherpa-onnx-c-api.so', 'libsherpa-onnx-cxx-api.so'],
    'ocr': ['libtesseract.so', 'libleptonica.so', 'libjpeg.so', 'libpngx.so'],
    'translate': ['libtranslate_jni.so'],
    'ijk': ['libijkffmpeg.so', 'libijksdl.so', 'libijkplayer.so'],
    'ali': ['libalivcffmpeg.so', 'libsaasCorePlayer.so', 'libsaasDownloader.so'],
    'mpv': ['libavutil.so', 'libswresample.so', 'libswscale.so', 'libavfilter.so',
            'libavcodec.so', 'libavformat.so', 'libavdevice.so', 'libc++_shared.so',
            'libmpv.so', 'libplayer.so'],
    'ffmpeg': ['liborangeffmpegkit.so'],
}
ABIS = ['arm64-v8a', 'armeabi-v7a']

# 与 app/build.gradle 的依赖版本一致；升级依赖必须同步改这里与 NativeLibManager 常量
REMOTE = {
    'sherpa': 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar',
    # jitpack 托管（Maven Central 上无此坐标，实测 404）
    'tess': 'https://jitpack.io/cz/adaptech/tesseract4android/'
            'tesseract4android/4.7.0/tesseract4android-4.7.0.aar',
    'mlk': 'https://dl.google.com/dl/android/maven2/com/google/mlkit/'
           'translate/17.0.2/translate-17.0.2.aar',
    'tr-arm64': 'https://repo1.maven.org/maven2/org/libtorrent4j/'
                'libtorrent4j-android-arm64/2.0.6-26/libtorrent4j-android-arm64-2.0.6-26.jar',
    'tr-arm': 'https://repo1.maven.org/maven2/org/libtorrent4j/'
              'libtorrent4j-android-arm/2.0.6-26/libtorrent4j-android-arm-2.0.6-26.jar',
    # 阿里云 SDK 只在阿里云 maven 有（Maven Central 返 404）
    'ali': 'https://maven.aliyun.com/repository/public/com/aliyun/sdk/android/'
           'AliyunPlayer/5.4.7.1-full/AliyunPlayer-5.4.7.1-full.aar',
    'mpv': 'https://repo1.maven.org/maven2/io/github/706412584/libmpv/1.0.0/libmpv-1.0.0.aar',
}

# 本地源（无网络时用；与 REMOTE 指向同一版本）
LOCAL_PATHS = {
    'sherpa': GC + '/8.13/transforms/*/transformed/jetified-sherpa-onnx-1.13.7',
    'tess': GC + '/modules-2/files-2.1/cz.adaptech.tesseract4android/'
            'tesseract4android/4.7.0/*/tesseract4android-4.7.0.aar',
    'mlk': GC + '/modules-2/files-2.1/com.google.mlkit/translate/17.0.2/*/translate-17.0.2.aar',
    'ali': GC + '/modules-2/files-2.1/com.aliyun.sdk.android/AliyunPlayer/'
           '5.4.7.1-full/*/*.aar',
    'mpv': GC + '/modules-2/files-2.1/io.github.706412584/libmpv/1.0.0/*/*.aar',
}

# ijk 的 so 在仓库内（submodule），源文件带调试符号（26.5MB），必须 strip 后打包
IJK_DIR = 'GSYVideoPlayer-source/gsyVideoPlayer-ex_so/src/main/jniLibs'
# ffmpeg 的 so 由本地 CMake 构建产出，CI 无法构建（45MB FFmpeg 静态库不入库、CI 无 NDK），
# 因此把 stripped 产物提交到仓库，本地与 CI 都从这里取，保证逐字节一致
FFMPEG_PREBUILT = 'tools/prebuilt/ffmpeg'


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


def _find_llvm_strip():
    """定位 NDK 的 llvm-strip。strip 结果必须与 AGP 一致（已实测两者逐字节相同）。"""
    candidates = []
    for var in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
        root = os.environ.get(var)
        if root:
            candidates.append(root)
    # 从 local.properties 的 sdk.dir 推 NDK
    try:
        with open(os.path.join(ROOT, 'local.properties')) as f:
            for line in f:
                if line.startswith('sdk.dir'):
                    sdk = line.split('=', 1)[1].strip().replace('\\:', ':').replace('\\', '/')
                    candidates += glob.glob(sdk + '/ndk/*')
    except IOError:
        pass
    for root in candidates:
        for exe in ('llvm-strip', 'llvm-strip.exe'):
            hits = glob.glob(os.path.join(root, 'toolchains/llvm/prebuilt/*/bin', exe))
            if hits:
                return sorted(hits)[-1]
    sys.exit('未找到 llvm-strip：请设置 ANDROID_NDK_HOME 或确认 SDK 下已安装 NDK')


_STRIP = None


def _strip_bytes(name, data):
    """strip 一份 so（只对未 strip 的源做；上游已 strip 的不要重复处理）。"""
    global _STRIP
    if _STRIP is None:
        _STRIP = _find_llvm_strip()
    tmp = os.path.join(FETCH_DIR, '_strip_tmp.so')
    os.makedirs(FETCH_DIR, exist_ok=True)
    with open(tmp, 'wb') as f:
        f.write(data)
    subprocess.check_call([_STRIP, '--strip-unneeded', tmp])
    with open(tmp, 'rb') as f:
        return f.read()


def _aar_bytes(aar_path, abi, name):
    with zipfile.ZipFile(aar_path) as z:
        member = 'jni/%s/%s' % (abi, name)
        try:
            return z.read(member)
        except KeyError:
            sys.exit('AAR %s 内缺少 %s' % (os.path.basename(aar_path), member))


def _jar_bytes(jar_path, abi, name):
    with zipfile.ZipFile(jar_path) as z:
        return z.read('lib/%s/%s' % (abi, name))


class Sources(object):
    """按需解析各 bundle 的源工件（--fetch 走网络，否则走本地缓存/仓库）。"""

    def __init__(self, fetch):
        self.fetch = fetch
        self._cache = {}

    def _get(self, key):
        if key not in self._cache:
            if self.fetch:
                self._cache[key] = _fetch(key)
            else:
                pat = LOCAL_PATHS[key]
                if key == 'sherpa':
                    # sherpa 的 so 在 AAR 内是 jni/<abi>/ 布局，解包成目录以复用
                    d = _glob1(pat, 'sherpa AAR')
                    self._cache[key] = d
                else:
                    self._cache[key] = _glob1(pat, key + ' AAR')
        return self._cache[key]

    def lib(self, bundle, abi, name):
        if bundle == 'asr':
            d = self._get('sherpa')
            if os.path.isdir(d):
                return open(os.path.join(d, 'jni', abi, name), 'rb').read()
            return _aar_bytes(d, abi, name)
        if bundle == 'ocr':
            return _aar_bytes(self._get('tess'), abi, name)
        if bundle == 'translate':
            return _aar_bytes(self._get('mlk'), abi, name)
        if bundle == 'ali':
            return _aar_bytes(self._get('ali'), abi, name)
        if bundle == 'mpv':
            return _aar_bytes(self._get('mpv'), abi, name)
        if bundle == 'torrent':
            suffix = 'arm64' if abi == 'arm64-v8a' else 'arm'
            jar = (self._get('tr-' + suffix) if self.fetch else _glob1(
                GC + '/modules-2/files-2.1/org.libtorrent4j/libtorrent4j-android-%s/'
                     '2.0.6-26/*/*.jar' % suffix, 'libtorrent4j-android-' + suffix))
            return _jar_bytes(jar, abi, name)
        if bundle == 'ijk':
            # 源文件带 19MB 调试符号，必须 strip 才能得到与 APK 一致的体积
            path = os.path.join(ROOT, IJK_DIR, abi, name)
            if not os.path.exists(path):
                sys.exit('未找到 %s（GSYVideoPlayer-source submodule 是否已初始化？）' % path)
            return _strip_bytes(name, open(path, 'rb').read())
        if bundle == 'ffmpeg':
            path = os.path.join(ROOT, FFMPEG_PREBUILT, abi, name)
            if not os.path.exists(path):
                sys.exit('未找到 %s\n请先构建 :orange-ffmpeg 并从 build/intermediates 拷贝 '
                         'stripped 产物到 %s/<abi>/' % (path, FFMPEG_PREBUILT))
            return open(path, 'rb').read()
        sys.exit('未知 bundle: %s' % bundle)


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


def check_release(tag, java_path, repo='706412584/orangeplayer'):
    """校验 GitHub Release 上的资产与 NativeLibManager 常量一致。

    zip 由本地构建后上传（deflate 字节流依赖 zlib 版本，跨环境不可复现，
    故 CI 不自行构建，只校验已上传的资产）。逐条比对每个资产的
    size 与 sha256 是否出现在 Java 源码中，返回错误列表。
    """
    import urllib.request
    import urllib.error
    src = open(java_path, encoding='utf-8').read()
    api = 'https://api.github.com/repos/%s/releases/tags/%s' % (repo, tag)
    req = urllib.request.Request(api, headers={'Accept': 'application/vnd.github+json'})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            release = json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            # Release 尚未创建。两个 workflow 在同一个 tag 上并行跑，Release 由
            # release-apk.yml 的 release job 创建；本 workflow 可能先到。给明确
            # 提示而不是抛裸 404 栈（那种失败看不出是竞态还是真的缺资产）。
            return ['Release %s 尚不存在（可能 release-apk.yml 还在构建中）；'
                    '待其完成并上传 zip 后重跑本 workflow' % tag]
        raise
    errors = []
    seen = set()
    for asset in release.get('assets', []):
        name = asset['name']
        if not name.endswith('.zip'):
            continue
        seen.add(name)
        # 资产在 GitHub 侧记录的 sha256 是 "sha256:<hex>"
        digest = (asset.get('digest') or '').replace('sha256:', '')
        size = asset['size']
        if not digest:
            errors.append('%s 缺少 sha256（GitHub 未返回 digest）' % name)
            continue
        if digest not in src:
            errors.append('%s sha256 %s 未出现在 NativeLibManager' % (name, digest))
        if '%dL' % size not in src and '%sL' % format(size, '_d') not in src:
            errors.append('%s size %d 未出现在 NativeLibManager' % (name, size))
    # 每个 bundle/abi 都应有一个 zip 资产
    for bundle in BUNDLES:
        for abi in ABIS:
            fname = '%s-%s.zip' % (bundle, abi)
            if fname not in seen:
                errors.append('Release %s 缺少资产 %s' % (tag, fname))
    return errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='build/native-libs')
    ap.add_argument('--fetch', action='store_true', help='直连官方源下载源工件（CI 用）')
    ap.add_argument('--only', help='只构建指定 bundle（逗号分隔）')
    ap.add_argument('--skip', help='跳过指定 bundle（逗号分隔）')
    ap.add_argument('--verify', metavar='JAVA',
                    help='校验 NativeLibManager 常量与 --out/manifest.json 一致')
    ap.add_argument('--check-release', metavar='TAG',
                    help='校验 GitHub Release 上的资产与 NativeLibManager 常量一致（CI 用）')
    args = ap.parse_args()

    if args.check_release:
        java = args.verify or ('palyerlibrary/src/main/java/com/orange/playerlibrary/'
                               'tool/NativeLibManager.java')
        errors = check_release(args.check_release, java)
        for e in errors:
            print('MISMATCH: %s' % e)
        print('check-release %s: %d 处不一致' % (args.check_release, len(errors)))
        sys.exit(1 if errors else 0)

    if args.verify:
        errors = verify(os.path.join(args.out, 'manifest.json'), args.verify)
        for e in errors:
            print('MISMATCH: %s' % e)
        print('verify %s: %d 处不一致' % (args.verify, len(errors)))
        sys.exit(1 if errors else 0)

    wanted = list(BUNDLES)
    if args.only:
        wanted = [b.strip() for b in args.only.split(',') if b.strip()]
        unknown = [b for b in wanted if b not in BUNDLES]
        if unknown:
            sys.exit('未知 bundle: %s' % unknown)
    if args.skip:
        skip = {b.strip() for b in args.skip.split(',') if b.strip()}
        wanted = [b for b in wanted if b not in skip]

    src = Sources(args.fetch)
    os.makedirs(args.out, exist_ok=True)
    manifest_path = os.path.join(args.out, 'manifest.json')
    # 增量：--only/--skip 时保留其余 bundle 的既有结果，避免整表重算
    manifest = {}
    if os.path.exists(manifest_path) and (args.only or args.skip):
        with open(manifest_path) as f:
            manifest = json.load(f)

    for bundle in wanted:
        for abi in ABIS:
            fname = '%s-%s.zip' % (bundle, abi)
            path = os.path.join(args.out, fname)
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
                for lib in BUNDLES[bundle]:
                    data = src.lib(bundle, abi, lib)
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

    with open(manifest_path, 'w') as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
    print('\nmanifest: %s' % manifest_path)


if __name__ == '__main__':
    main()
