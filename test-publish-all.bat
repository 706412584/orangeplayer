@echo off
REM 测试发布所有模块（不上传到 Maven Central）

echo ========================================
echo 测试发布所有模块
echo ========================================
echo.

echo [0/4] 读取版本号...
echo.

REM 从 maven-publish.gradle 读取版本号
powershell -Command "$line = Get-Content maven-publish.gradle | Select-String 'pomVersion = '; $line.Line -replace \".*pomVersion = '([^']+)'.*\", '$1'" > .version.tmp
set /p VERSION=<.version.tmp
del .version.tmp
if "%VERSION%"=="" (
    echo [ERROR] 无法读取版本号
    goto ERROR
)
echo   当前版本: %VERSION%
echo.

echo [1/4] 发布所有模块到本地仓库...
echo.

echo   [1/10] palyerlibrary...
call gradlew.bat :palyerlibrary:publishMavenPublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [2/10] gsyVideoPlayer-base...
call gradlew.bat :gsyVideoPlayer-base:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [3/10] gsyVideoPlayer-proxy_cache...
call gradlew.bat :gsyVideoPlayer-proxy_cache:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [4/10] gsyVideoPlayer-java...
call gradlew.bat :gsyVideoPlayer-java:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [5/10] gsyVideoPlayer-exo_player2...
call gradlew.bat :gsyVideoPlayer-exo_player2:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [6/10] gsyVideoPlayer-aliplay...
call gradlew.bat :gsyVideoPlayer-aliplay:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [7/10] gsyVideoPlayer-armv7a...
call gradlew.bat :gsyVideoPlayer-armv7a:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [8/10] gsyVideoPlayer-armv64...
call gradlew.bat :gsyVideoPlayer-armv64:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [9/10] gsyVideoPlayer-x86...
call gradlew.bat :gsyVideoPlayer-x86:publishReleasePublicationToLocalRepository
if errorlevel 1 goto ERROR

echo   [10/10] gsyVideoPlayer-x86_64...
call gradlew.bat :gsyVideoPlayer-x86_64:publishReleasePublicationToLocalRepository
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

echo [2/4] 收集所有模块的 artifacts...
if exist "temp_bundle_build" rmdir /s /q temp_bundle_build
mkdir temp_bundle_build

echo   复制 palyerlibrary...
if exist "palyerlibrary\build\repo\io" (
    xcopy /E /I /Y "palyerlibrary\build\repo\io" "temp_bundle_build\io"
)

REM Copy libmpv-central artifacts
echo   复制 libmpv...
if exist "libmpv-central\build\repo\io" (
    xcopy /E /I /Y "libmpv-central\build\repo\io" "temp_bundle_build\io"
)

REM Copy orangeplayer-mpv artifacts
echo   复制 orangeplayer-mpv...
if exist "orangeplayer-mpv\build\repo\io" (
    xcopy /E /I /Y "orangeplayer-mpv\build\repo\io" "temp_bundle_build\io"
)

echo   复制 GSYVideoPlayer 模块...
for %%m in (base proxy_cache java exo_player2 aliplay armv7a armv64 x86 x86_64) do (
    if exist "GSYVideoPlayer-source\gsyVideoPlayer-%%m\build\repo\io" (
        echo     - gsyVideoPlayer-%%m
        xcopy /E /I /Y "GSYVideoPlayer-source\gsyVideoPlayer-%%m\build\repo\io" "temp_bundle_build\io"
    )
)

echo.
echo [3/4] 创建 Bundle...
cd temp_bundle_build
powershell -Command "Compress-Archive -Path io -DestinationPath ..\maven-central\bundle-test.zip -Force"
cd ..

if not exist "maven-central\bundle-test.zip" (
    echo [ERROR] Bundle 创建失败
    goto ERROR
)

echo.
echo ========================================
echo 测试成功！
echo ========================================
echo.
echo Bundle 文件:
powershell -Command "Get-Item maven-central\bundle-test.zip | Select-Object Name, @{Name='Size(MB)';Expression={[math]::Round($_.Length/1MB,2)}}"
echo.
echo [4/4] 验证 Bundle 内容...
echo.
echo 包含的模块:
powershell -Command "$zip = [System.IO.Compression.ZipFile]::OpenRead('maven-central\bundle-test.zip'); $zip.Entries | Where-Object {$_.FullName -like '*/%VERSION%/*.aar' -or $_.FullName -like '*/%VERSION%/*.pom'} | Select-Object FullName | Format-Table -AutoSize; $zip.Dispose()"
echo.

REM 验证版本号一致性
echo 验证版本号一致性...
powershell -Command "$zip = [System.IO.Compression.ZipFile]::OpenRead('maven-central\bundle-test.zip'); $entries = $zip.Entries | Where-Object {$_.FullName -like '*/*.aar' -or $_.FullName -like '*/*.pom'}; $versions = $entries | ForEach-Object { if ($_.FullName -match '/(\d+\.\d+\.\d+)/') { $matches[1] } } | Select-Object -Unique; if ($versions.Count -eq 1 -and $versions[0] -eq '%VERSION%') { Write-Host '  ✓ 所有模块版本一致: %VERSION%' -ForegroundColor Green } else { Write-Host '  ✗ 版本号不一致！' -ForegroundColor Red; $versions | ForEach-Object { Write-Host \"    - $_\" } }; $zip.Dispose()"

echo.
echo 清理临时文件...
rmdir /s /q temp_bundle_build

echo.
echo 下一步: 运行 publish.bat 选择选项 3 上传到 Maven Central
echo.
pause
exit /b 0

:ERROR
echo.
echo [ERROR] 发布失败
if exist "temp_bundle_build" rmdir /s /q temp_bundle_build
pause
exit /b 1
