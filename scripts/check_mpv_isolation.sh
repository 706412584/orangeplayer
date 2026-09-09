#!/usr/bin/env bash
# mpv 集成架构一致性门禁（原 P7 原型隔离门禁，1.4.2 起架构已变）。
#
# 背景：P7 阶段（07b9ee1）mpv 仅存在于 experiments/orange-player-mpv 原型，
# 本脚本断言主构建零 mpv 引用。1.4.2 起 mpv 正式并入主构建（第五内核）：
#   - settings.gradle 纳入 :orangeplayer-mpv、:libmpv-central
#   - app 依赖 :orangeplayer-mpv（用户显式选择内核）
#   - libmpv-central 将上游 AAR 再分发到 Maven Central
#   - release-apk.yml 构建前准备 libmpv mavenrepo
# 因此改为断言"该有的引用在，不该有的没有"：
#   1. 必须引用：正式集成点的 mpv 坐标/模块存在
#   2. 禁止引用：app-tv / app-legacy 不依赖 mpv（仍限 arm 主 App 可选内核）
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail=0

# ===== 1. 正式集成点必须存在 mpv 引用 =====

require() {
    local file="$1"
    if [ ! -f "$file" ]; then
        echo "VIOLATION: 未找到正式集成文件 $file（当前目录：$repo_root）"
        fail=1
        return
    fi
    if ! grep -qi "orangeplayer-mpv" "$file"; then
        echo "VIOLATION: $file 缺少 orangeplayer-mpv 集成引用（mpv 为正式第五内核）"
        fail=1
    fi
}

require settings.gradle
require app/build.gradle
require .github/workflows/release-apk.yml

# libmpv 中央坐标必须可解析（本地 mavenrepo 兜底）
if ! grep -q "orangeplayer-mpv/mavenrepo" settings.gradle; then
    echo "VIOLATION: settings.gradle 缺少 libmpv mavenrepo 本地解析仓库"
    fail=1
fi

# ===== 2. 禁止引用：衍生 App 变体不得依赖 mpv =====

forbid() {
    local file="$1"
    if [ ! -f "$file" ]; then
        return   # 文件不存在不算失败（app-tv/app-legacy 可能未建）
    fi
    if grep -qi "orangeplayer-mpv" "$file"; then
        echo "VIOLATION: $file 依赖了 orangeplayer-mpv（mpv 仅限主 App 可选内核）"
        grep -ni "orangeplayer-mpv" "$file" | head -5
        fail=1
    fi
}

forbid app-tv/build.gradle
forbid app-legacy/build.gradle

# palyerlibrary 核心库不得编译依赖 mpv（可选内核经反射解耦，
# 见 OrangevideoView 的 findClass 调用——字节码上无硬引用）
if [ -f palyerlibrary/build.gradle ] && grep -qiE "^\s*(api|implementation|compileOnly)\s+project\(':orangeplayer-mpv'\)" palyerlibrary/build.gradle; then
    echo "VIOLATION: palyerlibrary 编译依赖了 orangeplayer-mpv（应经反射解耦）"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    echo "mpv 架构门禁失败"
    exit 1
fi
echo "mpv integration check passed"
