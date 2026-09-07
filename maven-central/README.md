# Maven Central 发布工具

## 功能

这个工具可以将 OrangePlayer 及其所有依赖模块发布到 Maven Central。

## 支持的模块

### 选项 1 & 2: 单模块发布
- **palyerlibrary** - OrangePlayer 核心库

### 选项 3: 全模块发布（推荐）
1. **palyerlibrary** - OrangePlayer 核心库
2. **gsyVideoPlayer-base** - GSYVideoPlayer 基础模块
3. **gsyVideoPlayer-proxy_cache** - 缓存代理模块
4. **gsyVideoPlayer-java** - Java 实现模块
5. **gsyVideoPlayer-exo_player2** - ExoPlayer 内核
6. **gsyVideoPlayer-aliplay** - 阿里云播放器内核
7. **gsyVideoPlayer-armv7a** - ARM 32位 native 库
8. **gsyVideoPlayer-armv64** - ARM 64位 native 库
9. **gsyVideoPlayer-x86** - x86 32位 native 库
10. **gsyVideoPlayer-x86_64** - x86 64位 native 库
11. **gsyVideoPlayer-ex_so** - IJK 加密 so（全架构）
12. **orange-downloader** - 下载管理
13. **orange-ffmpeg** - FFmpeg 合并
14. **libmpv** - 上游 libmpv-android AAR 再分发（**固定 1.0.0**，不随 pomVersion；需 `libmpv-central/libs/libmpv-1.0.0.aar` 就位）
15. **orangeplayer-mpv** - MPV 引擎内核（依赖 libmpv + 宿主自带 GSY）

> 注：libmpv 版本固定为上游 1.0.0，与主仓 1.4.0 不同属预期；全模块发布脚本对其单独校验。

## 使用方法

### 1. 配置凭证

在项目根目录的 `gradle.properties` 中添加：

```properties
ossrhUsername=your_username
ossrhPassword=your_password
signing.keyId=your_key_id
signing.password=your_key_password
signing.secretKeyRingFile=/path/to/secring.gpg
```

### 2. 运行发布脚本

```bash
# 从项目根目录运行
publish.bat
```

或直接运行：

```bash
cd maven-central
publish.bat
```

### 3. 选择操作

```
1. Quick Publish - 快速发布 palyerlibrary（推荐用于小更新）
2. Full Publish - 完整发布 palyerlibrary（包含清理和构建）
3. Publish All Modules - 发布所有 8 个模块（推荐用于新版本）
4. Check deployment status - 检查部署状态
5. Check Maven Central sync status - 检查 Maven Central 同步状态
6. Clear all deployments - 清理所有未发布的部署
```

## 发布流程

### 单模块发布（选项 1 或 2）

1. 构建并发布 `palyerlibrary` 到本地仓库
2. 创建 bundle.zip
3. 上传到 Maven Central Portal
4. 等待验证（2-5 分钟）
5. 在 Portal 中点击 "Publish"

### 全模块发布（选项 3）⭐ 推荐

1. 依次发布所有 8 个模块到本地仓库
2. 收集所有模块的 artifacts
3. 创建包含所有模块的 bundle.zip
4. 上传到 Maven Central Portal
5. 等待验证（5-10 分钟）
6. 在 Portal 中点击 "Publish"

**优势**：
- ✅ 一次性发布所有模块
- ✅ 版本号统一
- ✅ 用户可以直接使用，无需手动编译
- ✅ 依赖关系自动处理

## 用户使用方式

### 发布前（需要本地编译）

```gradle
// 用户需要克隆源码并引入模块
include ':gsyVideoPlayer-java'
include ':gsyVideoPlayer-armv7a'
// ...
```

### 发布后（直接使用）

```gradle
dependencies {
    // 核心库
    implementation 'io.github.706412584:orangeplayer:1.1.0-api14'
    
    // 选择需要的架构
    implementation 'io.github.706412584:gsyVideoPlayer-armv7a:1.1.0-api14'
    implementation 'io.github.706412584:gsyVideoPlayer-armv64:1.1.0-api14'
}
```

## 版本管理

版本号在 `maven-publish.gradle` 中配置：

```gradle
ext {
    pomVersion = '1.1.0-api14'  // 修改这里
}
```

所有模块使用相同的版本号。

## 检查发布状态

### 方法 1: 使用脚本（选项 4）

输入 deployment ID 检查状态。

### 方法 2: 访问 Portal

https://central.sonatype.com/publishing/deployments

### 方法 3: 检查 Maven Central（选项 5）

等待 15-30 分钟后，检查是否已同步到 Maven Central。

## 常见问题

### 1. 上传失败

**原因**: 凭证错误或网络问题

**解决**: 
- 检查 `gradle.properties` 中的凭证
- 确保网络可以访问 central.sonatype.com

### 2. 验证失败

**原因**: POM 文件格式错误或签名问题

**解决**:
- 检查 GPG 签名配置
- 确保所有必需字段都已填写

### 3. 模块依赖错误

**原因**: 模块间依赖关系配置错误

**解决**:
- 确保 `publish-gsy-modules.gradle` 正确处理了项目依赖
- 检查 POM 文件中的依赖声明

## 文件说明

- `publish.bat` - 主发布脚本
- `bundle.zip` - 生成的发布包（自动创建）
- `deployment_response.json` - 部署响应（自动创建）
- `status_response.json` - 状态查询响应（自动创建）

## 相关文档

- [发布指南](../docs/PUBLISH_LEGACY_SDK.md)
- [集成指南](../docs/SDK_INTEGRATION_GUIDE.md)
- [Maven Central 设置](../docs/MAVEN_CENTRAL_SETUP.md)
