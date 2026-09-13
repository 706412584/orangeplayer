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
