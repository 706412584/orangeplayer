# Project Rules

<!-- Project-specific instructions loaded into every conversation. -->

## 代码检索：优先使用 CodeGraph

本项目已建立 CodeGraph 索引（`.codegraph/`，4,835 文件 / 84,919 节点）。**查找代码时优先使用 codegraph MCP 工具，其次才是 Grep/Glob**：

- 找符号/定义/引用：`codegraph_query <name>`
- 理解一个模块或调用链：`codegraph_explore <topic>`（一次返回相关符号源码 + 调用路径）
- 查单个符号的调用者/被调者：`codegraph_callers <symbol>`、`codegraph_node <symbol>`
- 仅查文件路径或纯文本（日志字符串、注释等非符号内容）时用 Grep/Glob

索引过期时跑 `codegraph sync` 增量更新；`codegraph status` 查看索引状态。索引目录不入库（已在 .gitignore）。

## 发版：打 tag 前必须改 NativeLibManager.RELEASE_TAG

`release-native-libs.yml` 会校验 `NativeLibManager.RELEASE_TAG` 与推送的 tag 相等，
不等直接 `exit 1`。组件 zip 的下载地址由该常量拼出（`.../releases/download/<TAG>/<name>.zip`），
只改 tag 不改常量会让 app 去不存在的地址下载，用户看到「下载失败」。

所以每次发版的版本号有**四处**，缺一不可：

1. `.version`
2. `maven-publish.gradle` 的 `pomVersion`
3. `docs/CHANGELOG.md` 加章节
4. `palyerlibrary/.../tool/NativeLibManager.java` 的 `RELEASE_TAG`

组件 zip 若未随 release 上传（`gh release view vX.Y.Z` 里只有 APK），用
`tools/build_native_libs.py` 本地构建后 `gh release upload --clobber` 补传，
上传前先跑 `--verify` 确认 size/sha256 与 `NativeLibManager` 常量一致（必须 0 处不一致）。

发版后 gitee 需手动同步：`release.bat` 只推 origin。

## Gitee 同步：只能本地跑，且 full 版传不上去

**不能放 CI**：GitHub Actions 的海外 runner 往 Gitee 传文件会被跨境链路拖死，
与文件大小无关。实测（runner 上直接测）：GET 8KB 是 200/1.7s，但 1MB 的
multipart POST 就卡在 68% 后 90s 超时，9.7MB / 120MB 直接 0 字节超时。
上传速率从 13.8KB/s 衰减到 0，换 curl 参数、加超时、重试都无用。

**必须本地跑**（本机在国内，直连无此问题）：

```powershell
.\tools\sync-to-gitee.ps1 -Tag vX.Y.Z -DryRun   # 先看会传什么
.\tools\sync-to-gitee.ps1 -Tag vX.Y.Z           # 实际同步
```

**Gitee Release 附件单文件上限 100MB**（实测报错：「验证失败，文件大小已超过限制：100 MB」）。
所以 **full 版（含 so，114~118MB）传不上去，只能留在 GitHub** —— 这没问题，full 是
宿主内置 so 的对照验证包，不是面向用户的发行版；用户该下的是 slim（9.7MB，可正常同步）。
脚本已内置预检，超限的自动跳过并汇总，不会误报成失败。

别指望压缩绕过：APK 本身已压到 ~95%，gzip 只能到 42.5%（因包内 so 是 STORED
未压缩），压完 48MB 能传但要用户手动解压，得不偿失。

令牌读 `%USERPROFILE%\.gitee_token`（**仓库外**，避免被 `git add .` 带走）或环境变量
`GITEE_KEY`。`.gitignore` 另有 `gitee_token.txt` / `.gitee_token` / `*token.txt` 拦截规则作纵深防御。
