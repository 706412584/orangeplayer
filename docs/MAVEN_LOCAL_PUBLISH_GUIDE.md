# OrangePlayer 本地发布指引（Maven Central）

> 2026-09 整理。目标：每次发布不再"找不到密钥/凭据"，按本页顺序执行即可。

## 凭据与密钥总表（每次发布前先核对）

| 项 | 位置 | 当前值（要点） |
|---|---|---|
| Portal 账号密码 | `C:\Users\70641\.gradle\gradle.properties` | `ossrhUsername` / `ossrhPassword`（central.sonatype.com 登录令牌） |
| GPG 签名 keyId | 同上 | `signing.keyId=15D58DA4`（新 key，2026-09-07 起） |
| GPG 口令 | 同上 | `signing.password`（OrangePlayer2026!） |
| secring 文件 | `D:\android\projecet_iade\orangeplayer\maven-central\secring.gpg` | keyId 15D58DA4，指纹 `35150AD3C649D584136E5220E3F1B2B915D58DA4`，ed25519 |
| 旧 key（弃用） | `C:\Users\70641\secring.gpg` | C60913C4，**不要再用**；Portal 上注册的是新 key 公钥 |
| Portal UI | https://central.sonatype.com/publishing/deployments | VALIDATED 后手动点 Publish |

**注意**：项目内 `gradle.properties`（git 跟踪）**不含任何凭据**——凭据只在用户级
`C:\Users\70641\.gradle\gradle.properties`，Gradle 自动拾取。如果签名报错，先检查这份文件
的 `signing.secretKeyRingFile` 是否指向项目 `maven-central/secring.gpg`。

## 版本号位置（升版本要改两处）

1. 根目录 `.version` → `1.4.x`
2. `maven-publish.gradle` → `pomVersion = '1.4.x'`

GSY 子模块版本跟随 `rootProject.ext.pomVersion`（`GSYVideoPlayer-source/gradle/*.gradle`），无需单独改。

## 发布流程（15 模块 → 中央仓库）

### 第 1 步：本地发布全部模块

```bash
cd D:\android\projecet_iade\orangeplayer
# 交互菜单选 3（Publish All Modules），或逐模块执行：
call gradlew.bat :palyerlibrary:publishMavenPublicationToLocalRepository
# GSY 模块用 publishReleasePublicationToLocalRepositoryRepository（注意双 Repository）
# libmpv-central / orangeplayer-mpv / orange-downloader / orange-ffmpeg 用 publishMavenPublicationToLocalRepository
```

每个模块产物落在 `<module>/build/repo/io/github/706412584/<artifactId>/<version>/`。

### 第 2 步：组装 bundle（三个坑，都踩过）

```bash
cd maven-central
rm -rf temp_bundle_build
mkdir -p temp_bundle_build/io
# 复制 14 个模块（见下）的 build/repo/io/* 到 temp_bundle_build/io/
```

**坑 1 - libmpv 不能再传**：`io.github.706412584:libmpv:1.0.0` 已在中央仓库，
重复上传直接 FAILED（"already exists"）。1.4.2 起不打包 libmpv，除非它升版本。

**坑 2 - maven-metadata 必须剔除**：本地 `gradle publish` 会生成
`maven-metadata.xml*`（每模块目录下），Central 校验拒绝。打包前：

```bash
find temp_bundle_build -name "maven-metadata*" -delete
```

**坑 3 - 旧版本残留**：上传前确认 bundle 里只有当前版本：

```bash
find temp_bundle_build -type d -name "1.4.*" | sort -u   # 应只列当前版本
```

若混入旧版本（如 1.4.1），对应组件 "already exists" → 整个部署 FAILED。

### 第 3 步：打 zip 并上传

```bash
cd temp_bundle_build
rm -f ../bundle.zip
powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory((Get-Location).Path, 'D:\android\projecet_iade\orangeplayer\maven-central\bundle.zip', [System.IO.Compression.CompressionLevel]::Optimal, $false)"
cd ..
# Base64 编码 Basic Auth
AUTH_TOKEN=$(printf "用户名:密码" | base64)
curl -X POST -H "Authorization: Bearer $AUTH_TOKEN" -F "bundle=@bundle.zip" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=orangeplayer-<版本>&publishingType=USER_MANAGED"
```

- 返回 `HTTP 201` + 部署 ID 即上传成功
- 部署名规则：`orangeplayer-<版本>`（如 `orangeplayer-1.4.2`），**不要加 v2/v3 后缀**

### 第 4 步：查验证状态

```bash
curl -s -H "Authorization: Bearer $AUTH_TOKEN" \
  "https://central.sonatype.com/api/v1/publisher/deployments?name=orangeplayer-<版本>"
```

状态流转：`PENDING` → `VALIDATING` → `VALIDATED`（约 2~10 分钟）或 `FAILED`。
（单部署查询端点 `/deployments/{id}` 曾 404/500，用列表端点按 name 查更稳。）

### 第 5 步：手动 Publish（必须 UI 操作）

👉 https://central.sonatype.com/publishing/deployments → 对应部署（VALIDATED）→ **Publish**

### 第 6 步：验证中央同步（Publish 后约 10~30 分钟）

```bash
curl -s "https://repo1.maven.org/maven2/io/github/706412584/orangeplayer/<版本>/orangeplayer-<版本>.pom" | head -3
```

## 模块清单（1.4.2 = 14 模块，不含 libmpv）

| 工件 | 本地模块 |
|---|---|
| orangeplayer | palyerlibrary |
| gsyVideoPlayer-base / proxy_cache / java / exo_player2 / aliplay | GSYVideoPlayer-source/gsyVideoPlayer-* |
| gsyVideoPlayer-armv7a / armv64 / x86 / x86_64 / ex_so | GSYVideoPlayer-source/gsyVideoPlayer-* |
| orange-downloader | orange-downloader |
| orange-ffmpeg | orange-ffmpeg |
| orangeplayer-mpv | orangeplayer-mpv |
| ~~libmpv~~ | （已在中央 1.0.0，勿重复上传） |

## 历史教训

- 2026-09-07 GPG 旧 key 口令丢失 → 重建 ed25519 新 key 15D58DA4，推送 keyserver.ubuntu.com，
  Portal 自动拉取。重建后 C 盘 `~/.gradle/gradle.properties` 必须同步改指向新 key。
- 2026-09-08 1.4.2 三次失败根因：① 本地仓库残留 1.4.1 工件混入 bundle；② libmpv 重复上传。
- nextlib 实验（2026-09-09）：`io.github.anilbeesetti:nextlib-media3ext:1.11.0-0.14.0`
  与 libmpv 的 FFmpeg so 互斥，不可同时进 APK；注入代码留在 `OrangeExoPlayerManager`
  （反射注入，classpath 无该库时静默跳过），app 依赖注释保留备选。
