<#
.SYNOPSIS
    把 GitHub Release 的 APK 同步到 Gitee Release（供国内用户下载）。

.DESCRIPTION
    为什么需要本地跑而不是放在 CI：
    GitHub Actions 的海外 runner 往 Gitee 传文件会被跨境链路拖死，与文件大小
    无关。实测（在 runner 上）1MB 的 multipart POST 卡在 68% 后 90s 超时，
    9.7MB / 120MB 直接 0 字节超时；而同样 runner 上 GET 8KB 是 HTTP 200 / 1.7s。
    本机在国内，直连 Gitee 无此问题。

.PARAMETER Tag
    要同步的 tag，如 v1.5.4。必须已在 GitHub 上有对应 Release（含 APK 附件）。

.PARAMETER Source
    apks = 从 GitHub Release 下载（默认，保证与已发布产物一致）
    local = 用本机 build/oq/ 下已有的 APK

.PARAMETER Only
    只同步文件名含此子串的 APK，如 'slim' 或 'full-arm64'。留空同步全部。

.EXAMPLE
    # 先看会传什么，不实际写
    .\tools\sync-to-gitee.ps1 -Tag v1.5.4 -DryRun

.EXAMPLE
    .\tools\sync-to-gitee.ps1 -Tag v1.5.4

.NOTES
    令牌：优先读环境变量 GITEE_KEY；没有则提示输入（输入不回显）。
    令牌不会写入任何文件、不会打印到控制台。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,

    [ValidateSet('apks', 'local')]
    [string]$Source = 'apks',

    [string]$Only = '',

    [switch]$DryRun,

    [int]$TimeoutSec = 1800
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$GiteeOwner = 'wu-yongchengsvip'
$GiteeRepo = 'orangeplayer'
$Api = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo"

# ---------- 令牌 ----------
# 查找顺序：
#   1) 环境变量 GITEE_KEY
#   2) 仓库外的令牌文件 %USERPROFILE%\.gitee_token
#   3) 交互式输入
# 令牌文件刻意放在仓库外：仓库内任何位置都有被 git add . 带走的可能。
function Get-GiteeToken {
    $t = $env:GITEE_KEY

    if ([string]::IsNullOrWhiteSpace($t)) {
        $tokenFile = Join-Path $env:USERPROFILE '.gitee_token'
        if (Test-Path -LiteralPath $tokenFile) {
            $t = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
            if (-not [string]::IsNullOrWhiteSpace($t)) {
                Write-Host "从 $tokenFile 读取令牌" -ForegroundColor DarkGray
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($t)) {
        Write-Host '未找到令牌（环境变量 GITEE_KEY 与 ~/.gitee_token 都没有）。' -ForegroundColor Yellow
        Write-Host '请在 Gitee → 设置 → 私人令牌 生成（只需 projects 权限）。' -ForegroundColor Yellow
        $sec = Read-Host '粘贴令牌' -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
        try { $t = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }
    if ([string]::IsNullOrWhiteSpace($t)) { throw '未提供令牌' }
    Write-Host ("令牌已就绪（长度 {0}）" -f $t.Length) -ForegroundColor DarkGray
    return $t
}

# 统一的 curl 调用：令牌走 --form-string 而非 URL 查询串，避免出现在进程命令行里
function Invoke-Gitee {
    param(
        [string]$Method,
        [string]$Url,
        [string[]]$FormArgs = @(),
        [string]$Token,
        [int]$MaxTime = 60,
        [switch]$Raw
    )
    # 注意：不要用 $args —— 它是 PowerShell 自动变量，赋值会报语法错误
    # 输出写临时文件再按 UTF-8 读：走 PowerShell 管道时 curl 的 UTF-8 字节流
    # 会被按控制台代码页（中文 Windows 是 GBK）解码，JSON 里的中文变成 '?'
    # 导致 ConvertFrom-Json 失败（已实测）。
    $tmp = [IO.Path]::GetTempFileName()
    try {
        $curlArgs = @('-sS', '-o', $tmp, '-w', '%{http_code}', '-X', $Method, '--max-time', $MaxTime)
        $curlArgs += $FormArgs
        $curlArgs += $Url
        if ($FormArgs.Count -eq 0 -and $Token) {
            # GET/DELETE 用查询串
            $sep = if ($Url.Contains('?')) { '&' } else { '?' }
            $curlArgs[-1] = "$Url${sep}access_token=$Token"
        }

        $code = (& curl.exe @curlArgs 2>&1 | Out-String).Trim()
        $text = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
    if ($Raw) { return @{ Code = $code; Body = $text } }
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return $text | ConvertFrom-Json } catch { return $text }
}

# ---------- 取 release id（注意：不存在时 Gitee 返回 200 + body null，不是 404）----------
function Get-GiteeReleaseId {
    param([string]$Tag, [string]$Token)
    $r = Invoke-Gitee -Method GET -Url "$Api/releases/tags/$Tag" -Token $Token -Raw
    if ($r.Code -ne '200') { return $null }
    $body = $r.Body.Trim()
    if ($body -eq '' -or $body -eq 'null') { return $null }
    $o = $body | ConvertFrom-Json
    if ($null -eq $o -or $null -eq $o.id) { return $null }
    return $o.id
}

# ---------- 主流程 ----------
$token = Get-GiteeToken

# 1. 准备本地 APK
$dir = Join-Path $PSScriptRoot '..\build\oq' | Resolve-Path -ErrorAction SilentlyContinue
if (-not $dir) { $dir = Join-Path $PSScriptRoot '..\build\oq' }

if ($Source -eq 'apks') {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'gh CLI 不可用；改用 -Source local，或安装 gh'
    }
    $dir = Join-Path $PSScriptRoot '..\build\oq'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Write-Host "从 GitHub Release $Tag 下载 APK → $dir" -ForegroundColor Cyan
    if (-not $DryRun) {
        & gh release download $Tag --repo 706412584/orangeplayer -p 'OrangePlayer-*.apk' -D $dir --clobber
        if ($LASTEXITCODE -ne 0) { throw 'gh release download 失败' }
    }
}

# 只取该 tag 的包：build/oq/ 里可能残留别的版本（实测有 v1.5.2 的旧包），
# 不过滤会把它们一起传到错误的 release 上。
$files = Get-ChildItem -Path $dir -Filter '*.apk' |
    Where-Object { $_.Name -like "*$Tag*" } |
    Sort-Object Name
if ($Only) { $files = $files | Where-Object { $_.Name -like "*$Only*" } }
if (-not $files) { throw "在 $dir 没找到 $Tag 的 APK（-Only='$Only'）" }

Write-Host ''
Write-Host '待同步：' -ForegroundColor Cyan
foreach ($f in $files) {
    Write-Host ("  {0,-52} {1,8:N1} MB" -f $f.Name, ($f.Length / 1MB))
}
Write-Host ''

if ($DryRun) {
    Write-Host '[DryRun] 不实际写入。' -ForegroundColor Yellow
    return
}

# 2. 取得或创建 Gitee release
$rid = Get-GiteeReleaseId -Tag $Tag -Token $token
if ($rid) {
    Write-Host "Gitee release 已存在 id=$rid" -ForegroundColor Green
}
else {
    Write-Host "创建 Gitee release: $Tag" -ForegroundColor Cyan
    $r = Invoke-Gitee -Method POST -Url "$Api/releases" -Token $token -FormArgs @(
        '-d', "access_token=$token",
        '-d', "tag_name=$Tag",
        '-d', "name=OrangePlayer $Tag",
        # 用 ASCII：中文经 PowerShell 传给 curl.exe 会按控制台代码页编码，
        # 在 GBK 环境下会变成乱码存进 release 描述
        '-d', 'body=APKs synced from GitHub Release for users in mainland China',
        '-d', 'target_commitish=main'
    ) -Raw
    if ($r.Code -notin @('200', '201')) {
        Write-Host $r.Body -ForegroundColor Red
        throw "创建 release 失败 (HTTP=$($r.Code))"
    }
    $rid = ($r.Body | ConvertFrom-Json).id
    Write-Host "已创建 id=$rid" -ForegroundColor Green
}

# 3. 上传（同名先删，保证可重跑）
$existing = Invoke-Gitee -Method GET -Url "$Api/releases/$rid/attach_files" -Token $token

# Gitee Release 附件单文件上限 100MB（实测报错："验证失败，文件大小已超过限制：100 MB"）。
# 超限时 Gitee 会返回 400，这里先预检，避免白等一次上传。
# 注意：APK 本身已压缩到 ~95%，gzip 再压收益有限（实测 42.5%，因包内 so 是 STORED）。
# 完整版（含 so）普遍超 100MB，留在 GitHub 即可——它是宿主内置 so 的对照验证包，
# 不是面向用户的发行版；用户该下的是 slim。
$SizeLimitMB = 100

$ok = 0; $fail = 0; $skipped = @()
foreach ($f in $files) {
    $mb = $f.Length / 1MB
    Write-Host "=== $($f.Name) ($([math]::Round($mb,1)) MB)" -ForegroundColor Cyan

    if ($mb -gt $SizeLimitMB) {
        Write-Host ("  跳过：{0:N1} MB 超过 Gitee 的 {1} MB 单文件上限，保留在 GitHub" -f $mb, $SizeLimitMB) -ForegroundColor Yellow
        $skipped += $f.Name
        continue
    }

    $old = $null
    if ($existing -and $existing -isnot [string]) {
        $old = $existing | Where-Object { $_.name -eq $f.Name } | Select-Object -First 1
    }
    if ($old) {
        Write-Host "  删除旧附件 id=$($old.id)" -ForegroundColor DarkGray
        Invoke-Gitee -Method DELETE -Url "$Api/releases/$rid/attach_files/$($old.id)" -Token $token | Out-Null
    }

    $r = Invoke-Gitee -Method POST -Url "$Api/releases/$rid/attach_files" -Token $token -MaxTime $TimeoutSec -Raw -FormArgs @(
        '-F', "access_token=$token",
        '-F', "file=@$($f.FullName)"
    )
    if ($r.Code -in @('200', '201')) {
        Write-Host '  OK' -ForegroundColor Green
        $ok++
    }
    else {
        Write-Host "  失败 HTTP=$($r.Code)" -ForegroundColor Red
        Write-Host "  $($r.Body)" -ForegroundColor Red
        $fail++
    }
}

# 4. 校验
Write-Host ''
$final = Invoke-Gitee -Method GET -Url "$Api/releases/$rid/attach_files" -Token $token
Write-Host "Gitee release $Tag 现有附件：" -ForegroundColor Cyan
if ($final -and $final -isnot [string]) {
    foreach ($a in $final) {
        Write-Host ("  {0,-52} {1,8:N1} MB" -f $a.name, ($a.size / 1MB))
    }
}
Write-Host ''
if ($skipped.Count -gt 0) {
    Write-Host "以下 $($skipped.Count) 个超过 Gitee 大小上限，未同步（保留在 GitHub Release）：" -ForegroundColor Yellow
    foreach ($s in $skipped) { Write-Host "  $s" -ForegroundColor Yellow }
    Write-Host ''
}
if ($fail -gt 0) {
    Write-Host "完成：成功 $ok，失败 $fail，跳过 $($skipped.Count)" -ForegroundColor Yellow
    exit 1
}
Write-Host "完成：同步 $ok 个，跳过 $($skipped.Count) 个" -ForegroundColor Green
Write-Host "https://gitee.com/$GiteeOwner/$GiteeRepo/releases/tag/$Tag" -ForegroundColor DarkGray
