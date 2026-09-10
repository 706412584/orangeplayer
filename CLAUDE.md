# Project Rules

<!-- Project-specific instructions loaded into every conversation. -->

## 代码检索：优先使用 CodeGraph

本项目已建立 CodeGraph 索引（`.codegraph/`，4,835 文件 / 84,919 节点）。**查找代码时优先使用 codegraph MCP 工具，其次才是 Grep/Glob**：

- 找符号/定义/引用：`codegraph_query <name>`
- 理解一个模块或调用链：`codegraph_explore <topic>`（一次返回相关符号源码 + 调用路径）
- 查单个符号的调用者/被调者：`codegraph_callers <symbol>`、`codegraph_node <symbol>`
- 仅查文件路径或纯文本（日志字符串、注释等非符号内容）时用 Grep/Glob

索引过期时跑 `codegraph sync` 增量更新；`codegraph status` 查看索引状态。索引目录不入库（已在 .gitignore）。
