#!/usr/bin/env python3
"""给 iApp 侧用的本地 aar 补「接口 default 方法转发桥接」，修复 AbstractMethodError。

== 为什么需要这个脚本 ==
iApp 对 sdk/ 下每个依赖文件【独立 dex】（实测：一个 aar 恰好对应一个 dex），
且 dexer 以 min-api < 24 运行，接口的 default 方法会被脱糖成「抽象方法 +
接口$-CC.$default$xxx」。实现类与接口分属不同 dex 时，dexer 看不到接口的
default 信息，不给实现类补转发方法 → 运行时 AbstractMethodError。

实测 d8 警告（直接点名根因）：
  Type `androidx.media3.exoplayer.analytics.AnalyticsListener` was not found,
  it is required for default or static interface methods desugaring of
  `tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer`

换 maven 依赖治不了：exo_player2 等本地 aar 没有 1.5.4 远程坐标
（Maven Central 上 io.github.706412584 只发到 1.4.2），永远是本地文件、
永远单独 dex。

== 重要 ==
本补丁【每次更新本地 aar 后都要重打】，否则立刻退回崩溃。
所以流程是：更新 sdk/ 下的 aar → 跑本脚本 → 推送产物到设备。

用法：
  python tools/iapp-desugar-bridge/patch_aars.py --in D:/download/iapp_aars --out D:/download/patched
  python tools/iapp-desugar-bridge/patch_aars.py --in <dir> --out <dir> --verify   # 补完自检
  python tools/iapp-desugar-bridge/patch_aars.py --in <dir> --out <dir> --only a.aar,b.aar

  # 只分析不修改（排查用）
  python tools/iapp-desugar-bridge/patch_aars.py --analyze-only --in <dir>

参数：
  --in    含 .aar 的目录（也接受单个 .aar / .jar）
  --out   输出目录（补丁后 aar 写到这里，原文件不动）
  --only  只处理指定文件（逗号分隔文件名）
  --verify  补完后跑缺口分析，跨 aar 缺口必须为 0，否则退出码 1
  --asm   ASM jar 路径（默认自动查找 Gradle 缓存 / 下载到 build/）

依赖：JDK 11+（用 javac/java 编译并运行工具）、ASM 9.x。
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(ROOT, 'build', 'iapp-desugar-bridge')

# 与 Gradle 缓存里常见版本一致；换版本需同步改 ASM_SHA256
ASM_VERSION = '9.9'
ASM_URLS = [
    'https://maven.aliyun.com/repository/public/org/ow2/asm/asm/%s/asm-%s.jar'
    % (ASM_VERSION, ASM_VERSION),
    'https://repo1.maven.org/maven2/org/ow2/asm/asm/%s/asm-%s.jar'
    % (ASM_VERSION, ASM_VERSION),
]
ASM_SHA256 = '03d99a74ad1ee5c71334ef67437f4ef4fe3488caa7c96d8645abc73c8e2017d4'

JDK_CANDIDATES = [
    os.environ.get('JAVA_HOME'),
    r'D:\android\jbr',
    r'D:\android\IntelliJ IDEA 2025.3.1.1\jbr',
    '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
    os.path.expanduser('~/.jdks'),
]


def find_jdk():
    """找一个带 javac 的 JDK 11+。"""
    for cand in JDK_CANDIDATES:
        if not cand:
            continue
        for exe in ('javac', 'javac.exe'):
            p = os.path.join(cand, 'bin', exe)
            if os.path.isfile(p):
                return os.path.join(cand, 'bin')
        # ~/.jdks 下可能有多个
        if os.path.isdir(cand) and cand.endswith('.jdks'):
            for sub in sorted(os.listdir(cand), reverse=True):
                for exe in ('javac', 'javac.exe'):
                    p = os.path.join(cand, sub, 'bin', exe)
                    if os.path.isfile(p):
                        return os.path.join(cand, sub, 'bin')
    # 退回 PATH
    for exe in ('javac', 'javac.exe'):
        found = shutil.which(exe)
        if found:
            return os.path.dirname(found)
    return None


def find_asm(explicit=None):
    """找 ASM jar：显式路径 → Gradle 缓存 → 下载。"""
    if explicit:
        if not os.path.isfile(explicit):
            sys.exit('ASM jar 不存在: %s' % explicit)
        return explicit

    cache = os.path.expanduser('~/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm')
    if os.path.isdir(cache):
        for ver in sorted(os.listdir(cache), reverse=True):
            vd = os.path.join(cache, ver)
            for r, _, fs in os.walk(vd):
                for f in fs:
                    if f == 'asm-%s.jar' % ver:
                        return os.path.join(r, f)

    dest = os.path.join(WORK, 'asm-%s.jar' % ASM_VERSION)
    if os.path.isfile(dest):
        return dest
    os.makedirs(WORK, exist_ok=True)
    last = None
    for url in ASM_URLS:
        try:
            print('下载 ASM: %s' % url)
            urllib.request.urlretrieve(url, dest)
            break
        except Exception as e:      # noqa: BLE001
            last = e
            print('  失败: %s' % e)
    else:
        sys.exit('无法获取 ASM jar（最后一次错误: %s）\n'
                 '可手动下载 asm-%s.jar 后用 --asm 指定。' % (last, ASM_VERSION))

    got = hashlib.sha256(open(dest, 'rb').read()).hexdigest()
    if got != ASM_SHA256:
        os.remove(dest)
        sys.exit('ASM jar sha256 不匹配\n  期望 %s\n  实际 %s' % (ASM_SHA256, got))
    return dest


def build_tools(jdk_bin, asm, outdir):
    """编译 PatchAar / AnalyzeGaps。"""
    os.makedirs(outdir, exist_ok=True)
    javac = os.path.join(jdk_bin, 'javac')
    if not os.path.isfile(javac):
        javac += '.exe'
    srcs = [os.path.join(HERE, 'PatchAar.java'), os.path.join(HERE, 'AnalyzeGaps.java'),
            os.path.join(HERE, 'AnalyzeStaticCalls.java')]
    cmd = [javac, '-encoding', 'UTF-8', '-cp', asm, '-d', outdir] + srcs
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit('javac 失败:\n%s\n%s' % (r.stdout, r.stderr))
    print('工具已编译 -> %s' % outdir)


def run_java(jdk_bin, asm, classes_dir, main, args):
    java = os.path.join(jdk_bin, 'java')
    if not os.path.isfile(java):
        java += '.exe'
    cp = os.pathsep.join([classes_dir, asm])
    cmd = [java, '-Dfile.encoding=UTF-8', '-cp', cp, main] + args
    r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8',
                       errors='replace')
    return r.returncode, (r.stdout or '') + (r.stderr or '')


def collect_aars(inp, only=None):
    """收集输入目录下的全部 aar；返回 (全部路径, 待处理文件名集合或 None)。

    注意：PatchAar 需要【全部】aar 作为 classpath 才能解析接口与父类链，
    --only 只限定「补丁哪些」，不能缩减 classpath，否则接口信息残缺、
    一个桥接都补不出来。
    """
    if os.path.isfile(inp):
        return [inp], {os.path.basename(inp)}
    if not os.path.isdir(inp):
        sys.exit('输入路径不存在: %s' % inp)
    files = sorted(f for f in os.listdir(inp) if f.endswith('.aar'))
    if not files:
        sys.exit('在 %s 下没找到 .aar' % inp)
    paths = [os.path.join(inp, f) for f in files]

    targets = None
    if only:
        want = {x.strip() for x in only.split(',') if x.strip()}
        missing = want - set(files)
        if missing:
            sys.exit('--only 指定的文件在 --in 里找不到: %s' % sorted(missing))
        targets = want
    return paths, targets


def stage_inputs(paths, stagedir):
    """把输入拷进临时目录（原文件不动）。"""
    if os.path.isdir(stagedir):
        shutil.rmtree(stagedir)
    os.makedirs(stagedir)
    for p in paths:
        shutil.copy2(p, os.path.join(stagedir, os.path.basename(p)))


def extract_aars(aardir, destdir):
    """把 aar 里的 classes.jar 展开成 <name>/<package>/<Class>.class 供分析。"""
    if os.path.isdir(destdir):
        shutil.rmtree(destdir)
    os.makedirs(destdir)
    n = 0
    for f in sorted(os.listdir(aardir)):
        if not f.endswith('.aar'):
            continue
        sub = os.path.join(destdir, f[:-4])
        os.makedirs(sub, exist_ok=True)
        with zipfile.ZipFile(os.path.join(aardir, f)) as z:
            if 'classes.jar' not in z.namelist():
                continue
            cj = os.path.join(destdir, '_cj_%s.jar' % f[:-4])
            with open(cj, 'wb') as fh:
                fh.write(z.read('classes.jar'))
        with zipfile.ZipFile(cj) as z:
            z.extractall(sub)
        os.remove(cj)
        n += 1
    return n


def verify(outdir, jdk_bin, asm, classes_dir):
    """跑 AnalyzeGaps + AnalyzeStaticCalls，两类跨文件缺口都必须为 0。"""
    flat = os.path.join(WORK, 'verify-extracted')
    n = extract_aars(outdir, flat)
    print('展开 %d 个 aar 供分析' % n)

    code, out = run_java(jdk_bin, asm, classes_dir, 'AnalyzeGaps', [flat])
    print(out.strip())
    if code != 0:
        return False, 'AnalyzeGaps 执行失败'

    total = None
    cross = []
    for line in out.splitlines():
        if line.startswith('TOTAL GAPS:'):
            total = int(line.split(':')[1].strip())
        if '  ->  ' in line:
            left = line.split('  ->  ')[0].strip()
            right = line.split('  ->  ')[1].split('(')[0].strip()
            # 同 aar 缺口由 d8 自己处理，不算问题
            if left != right:
                cross.append(line.strip())
    if cross:
        return False, '仍有跨 aar 缺口 %d 组:\n%s' % (len(cross), '\n'.join(cross))
    if total is None:
        return False, '未能解析 TOTAL GAPS'

    # 静态接口方法调用（另一脱糖面）：跨文件调用点必须已被重定向到 $-CC
    scode, sout = run_java(jdk_bin, asm, classes_dir, 'AnalyzeStaticCalls', [flat])
    print(sout.strip())
    if scode != 0:
        return False, '仍有跨文件静态接口调用未重定向（见上方 [缺口] 行）'

    return True, ('跨 aar 缺口 0（总缺口 %d，均为同 aar、由 d8 自行处理）；'
                  '跨文件静态接口调用缺口 0' % total)


def main():
    ap = argparse.ArgumentParser(description='给 iApp 用的本地 aar 补 default 方法桥接')
    ap.add_argument('--in', dest='inp', required=True, help='含 .aar 的目录，或单个 .aar')
    ap.add_argument('--out', help='输出目录（补丁后的 aar 写到这里）')
    ap.add_argument('--only', help='只处理指定文件（逗号分隔）')
    ap.add_argument('--verify', action='store_true', help='补完后自检，跨 aar 缺口必须为 0')
    ap.add_argument('--analyze-only', action='store_true', help='只分析不修改')
    ap.add_argument('--asm', help='ASM jar 路径（默认自动查找/下载）')
    args = ap.parse_args()

    if not args.analyze_only and not args.out:
        sys.exit('需要 --out（或用 --analyze-only 只分析）')

    jdk_bin = find_jdk()
    if not jdk_bin:
        sys.exit('找不到 JDK（需要 javac 11+）。设 JAVA_HOME 或用带 javac 的 JDK。')
    print('JDK: %s' % jdk_bin)

    asm = find_asm(args.asm)
    print('ASM: %s' % asm)

    classes_dir = os.path.join(WORK, 'classes')
    build_tools(jdk_bin, asm, classes_dir)

    paths, targets = collect_aars(args.inp, args.only)

    if args.analyze_only:
        staged = os.path.join(WORK, 'analyze')
        stage_inputs(paths, staged)
        flat = os.path.join(WORK, 'analyze-extracted')
        n = extract_aars(staged, flat)
        print('展开 %d 个 aar 供分析\n' % n)
        code, out = run_java(jdk_bin, asm, classes_dir, 'AnalyzeGaps', [flat])
        print(out.strip())
        sys.exit(code)

    staged = os.path.join(WORK, 'staged')
    stage_inputs(paths, staged)

    if len(paths) == 1:
        print('警告：只提供了 1 个 aar 作 classpath。接口信息可能不完整，'
              '补丁数会偏少甚至为 0。\n'
              '      正确用法是 --in 指向【含全部依赖的目录】，'
              '需要限定范围时用 --only。\n')

    os.makedirs(args.out, exist_ok=True)
    todo = len(targets) if targets else len(paths)
    print('\n处理 %d 个 aar（classpath 用全部 %d 个）-> %s\n'
          % (todo, len(paths), args.out))
    cmd = [staged, args.out] + (sorted(targets) if targets else [])
    code, out = run_java(jdk_bin, asm, classes_dir, 'PatchAar', cmd)
    print(out.strip())
    if code != 0:
        sys.exit('PatchAar 失败（退出码 %d）' % code)

    if args.verify:
        if targets:
            print('\n提示：--only 模式下自检不适用（输出不含未选中的 aar，'
                  '跨 aar 缺口会假阳性），已跳过。')
        else:
            print('\n=== 自检 ===')
            ok, msg = verify(args.out, jdk_bin, asm, classes_dir)
            print(msg)
            if not ok:
                sys.exit(1)
    print('\n完成。别忘了把产物推送到设备 sdk/ 并重新编译安装。')


if __name__ == '__main__':
    main()
