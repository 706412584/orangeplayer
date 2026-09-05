#!/usr/bin/env bash
# P7 门禁：断言 mpv 原型与主构建/发布路径物理隔离。
# 在 CI (ci-test.yml) 中先于 Gradle 任务执行。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail=0

check() {
    local file="$1"
    if [ ! -f "$file" ]; then
        echo "VIOLATION: 未找到待检查文件 $file（当前目录：$repo_root）"
        fail=1
        return
    fi
    if grep -qi "mpv" "$file"; then
        echo "VIOLATION: $file 引用了 mpv（原型必须隔离在 experiments/orange-player-mpv）"
        grep -ni "mpv" "$file" | head -5
        fail=1
    fi
}

check settings.gradle
check palyerlibrary/build.gradle
check maven-publish.gradle
check scripts/publish-module.gradle
check publish-gsy-modules.gradle
check .github/workflows/publish-maven-central.yml
check .github/workflows/release-apk.yml

# app 模块不得依赖 mpv（app-tv/app-legacy 同理）
for f in app/build.gradle app-tv/build.gradle app-legacy/build.gradle; do
    check "$f"
done

if [ "$fail" -ne 0 ]; then
    echo "mpv 隔离门禁失败"
    exit 1
fi
echo "mpv isolation check passed"
