@echo off
REM 清理所有旧的构建产物并重新构建

echo ========================================
echo 清理并重新构建所有模块
echo ========================================
echo.

echo [0/4] 读取版本号...
echo.

REM 从 maven-publish.gradle 读取版本号
for /f "tokens=2 delims='" %%a in ('findstr /c:"pomVersion =" maven-publish.gradle') do set VERSION=%%a

if "%VERSION%"=="" (
    echo [ERROR] 无法读取版本号
    goto ERROR
)
echo   当前版本: %VERSION%
echo.

echo [1/4] 清理所有构建产物...
echo.

REM 清理 Gradle 缓存
echo   清理 Gradle 构建缓存...
call gradlew.bat clean
if errorlevel 1 goto ERROR

REM 删除所有 build/repo 目录
echo   删除所有本地仓库目录...
if exist "palyerlibrary\build\repo" rmdir /s /q "palyerlibrary\build\repo"
for %%m in (base proxy_cache java armv7a armv64 x86 x86_64) do (
    if exist "GSYVideoPlayer-source\gsyVideoPlayer-%%m\build\repo" (
        echo     - gsyVideoPlayer-%%m\build\repo
        rmdir /s /q "GSYVideoPlayer-source\gsyVideoPlayer-%%m\build\repo"
    )
)

REM 删除旧的 bundle 文件
echo   删除旧的 bundle 文件...
if exist "maven-central\bundle.zip" del /q "maven-central\bundle.zip"
if exist "maven-central\bundle-test.zip" del /q "maven-central\bundle-test.zip"

echo.
echo [2/4] 重新发布所有模块到本地仓库...
echo.

echo   [1/10] palyerlibrary...
call gradlew.bat :palyerlibrary:publishMavenPublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [2/10] gsyVideoPlayer-base...
call gradlew.bat :gsyVideoPlayer-base:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [3/10] gsyVideoPlayer-proxy_cache...
call gradlew.bat :gsyVideoPlayer-proxy_cache:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [4/10] gsyVideoPlayer-java...
call gradlew.bat :gsyVideoPlayer-java:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [5/10] gsyVideoPlayer-armv7a...
call gradlew.bat :gsyVideoPlayer-armv7a:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [6/10] gsyVideoPlayer-armv64...
call gradlew.bat :gsyVideoPlayer-armv64:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [7/10] gsyVideoPlayer-x86...
call gradlew.bat :gsyVideoPlayer-x86:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [8/10] gsyVideoPlayer-x86_64...
call gradlew.bat :gsyVideoPlayer-x86_64:publishReleasePublicationToLocalRepositoryRepository
if errorlevel 1 goto ERROR

echo   [9/10] orange-downloader...
call gradlew.bat :orange-downloader:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [10/10] orange-ffmpeg...
call gradlew.bat :orange-ffmpeg:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [11/12] libmpv-central（上游 AAR 再分发）...
call gradlew.bat :libmpv-central:publishMavenPublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [12/12] orangeplayer-mpv...
call gradlew.bat :orangeplayer-mpv:publishMavenPublicationToLocalRepository
if errorlevel 1 goto ERROR

echo.
echo [SUCCESS] 所有模块已发布到本地仓库
echo.

echo [3/4] 验证版本号...
echo.

REM 检查 palyerlibrary 版本
if exist "palyerlibrary\build\repo\io\github\706412584\orangeplayer\%VERSION%" (
    echo   ✓ orangeplayer %VERSION%
) else (
    echo   ✗ orangeplayer %VERSION% 未找到
    goto ERROR
)

REM 检查 orange-downloader 与 orange-ffmpeg 版本
if exist "orange-downloader\build\repo\io\github\706412584\orange-downloader\%VERSION%" (
    echo   ✓ orange-downloader %VERSION%
) else (
    echo   ✗ orange-downloader %VERSION% 未找到
    goto ERROR
)

if exist "orange-ffmpeg\build\repo\io\github\706412584\orange-ffmpeg\%VERSION%" (
    echo   ✓ orange-ffmpeg %VERSION%
) else (
    echo   ✗ orange-ffmpeg %VERSION% 未找到
    goto ERROR
)

REM libmpv-central 固定版本 1.0.0（跟随上游，不随 pomVersion）
if exist "libmpv-central\build\repo\io\github\706412584\libmpv\1.0.0" (
    echo   ✓ libmpv 1.0.0
) else (
    echo   ✗ libmpv 1.0.0 未找到（先执行 libmpv-central 本地发布）
    goto ERROR
)

if exist "orangeplayer-mpv\build\repo\io\github\706412584\orangeplayer-mpv\%VERSION%" (
    echo   ✓ orangeplayer-mpv %VERSION%
) else (
    echo   ✗ orangeplayer-mpv %VERSION% 未找到
    goto ERROR
)

REM 检查 GSYVideoPlayer 模块版本
for %%m in (base proxy_cache java armv7a armv64 x86 x86_64) do (
    if exist "GSYVideoPlayer-source\gsyVideoPlayer-%%m\build\repo\io\github\706412584\gsyVideoPlayer-%%m\%VERSION%" (
        echo   ✓ gsyVideoPlayer-%%m %VERSION%
    ) else (
        echo   ✗ gsyVideoPlayer-%%m %VERSION% 未找到
        goto ERROR
    )
)

echo.
echo [4/4] 创建版本信息文件...
echo.

REM 创建版本信息文件供其他脚本使用
echo %VERSION%> .version
echo   版本信息已保存到 .version 文件

echo.
echo ========================================
echo 清理并重新构建成功！
echo ========================================
echo.
echo 所有模块版本: %VERSION%
echo.
echo 下一步: 运行 test-publish-all.bat 创建 bundle
echo.
pause
exit /b 0

:ERROR
echo.
echo [ERROR] 操作失败
pause
exit /b 1
