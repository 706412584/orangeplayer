package com.orange.downloader.task;

import android.text.TextUtils;

import com.orange.downloader.VideoDownloadException;
import com.orange.downloader.common.DownloadConstants;
import com.orange.downloader.m3u8.M3U8;
import com.orange.downloader.m3u8.M3U8Constants;
import com.orange.downloader.m3u8.M3U8Seg;
import com.orange.downloader.model.VideoTaskItem;
import com.orange.downloader.utils.DownloadExceptionUtils;
import com.orange.downloader.utils.HttpUtils;
import com.orange.downloader.utils.LogUtils;
import com.orange.downloader.utils.VideoDownloadUtils;
import com.orange.downloader.utils.VideoStorageUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class M3U8VideoDownloadTask extends VideoDownloadTask {
    private static final int CONTINUOUS_SUCCESS_TS_THRESHOLD = 6;
    private final Object mFileLock = new Object();

    private volatile int mM3U8DownloadPoolCount;
    private volatile int mContinuousSuccessTsCount;   //连续请求ts成功的个数

    private final M3U8 mM3U8;
    private List<M3U8Seg> mTsList;
    /** 已下载的密钥 URI（同一 key 被整条 playlist 引用，需去重） */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> mDownloadedKeyUris =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 实际下载到的分片是否为明文 TS（站点声明可能与实际不符，见 detectSegmentsArePlainText） */
    private volatile boolean mSegmentsArePlainText = false;
    /** 是否有过 key 下载失败（收尾时按分片实际内容决定放行还是报错） */
    private volatile boolean mKeyDownloadFailed = false;
    private volatile int mCurTs = 0;
    private int mTotalTs;
    private long mTotalSize;

    public M3U8VideoDownloadTask(VideoTaskItem taskItem, M3U8 m3u8, Map<String, String> headers) {
        super(taskItem, headers);
        mM3U8 = m3u8;
        mTsList = m3u8.getTsList();
        mTotalTs = mTsList.size();
        mPercent = taskItem.getPercent();
        if (mHeaders == null) {
            mHeaders = new HashMap<>();
        }
        mHeaders.put("Connection", "close");
        taskItem.setTotalTs(mTotalTs);
        taskItem.setCurTs(mCurTs);
    }

    private void initM3U8Ts() {
        long tempCurrentCachedSize = 0;
        int tempCurTs = 0;
        for (M3U8Seg ts : mTsList) {
            File tempTsFile = new File(mSaveDir, ts.getIndexName());
            if (tempTsFile.exists() && tempTsFile.length() > 0) {
                ts.setTsSize(tempTsFile.length());
                tempCurTs++;
            } else {
                break;
            }
        }
        mCurTs = tempCurTs;
        mCurrentCachedSize = tempCurrentCachedSize;
        if (mCurTs == mTotalTs) {
            mTaskItem.setIsCompleted(true);
        }
    }


    @Override
    public void startDownload() {
        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] startDownload() called, url=" + mTaskItem.getUrl());
        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] totalTs=" + mTotalTs + ", saveDir=" + mSaveDir.getAbsolutePath());
        mDownloadTaskListener.onTaskStart(mTaskItem.getUrl());
        initM3U8Ts();
        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] initM3U8Ts done, curTs=" + mCurTs + ", isCompleted=" + mTaskItem.isCompleted());
        startDownload(mCurTs);
    }

    private void startDownload(int curDownloadTs) {
        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] startDownload(curDownloadTs=" + curDownloadTs + ") called");
        if (mTaskItem.isCompleted()) {
            LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] Task already completed, calling notifyDownloadFinish");
            notifyDownloadFinish();
            return;
        }
        mCurTs = curDownloadTs;
        LogUtils.i(DownloadConstants.TAG, "startDownload curDownloadTs = " + curDownloadTs);
        mDownloadExecutor = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        for (int index = curDownloadTs; index < mTotalTs; index++) {
            final M3U8Seg ts = mTsList.get(index);
            mDownloadExecutor.execute(() -> {
                try {
                    downloadTsTask(ts);
                } catch (Exception e) {
                    LogUtils.w(DownloadConstants.TAG, "M3U8TsDownloadThread download failed, exception=" + e);
                    notifyDownloadError(e);
                }
            });
        }

        notifyDownloadFinish(mCurrentCachedSize);
    }

    private void downloadTsTask(M3U8Seg ts) throws Exception {
        if (ts.hasInitSegment()) {
            String tsInitSegmentName = ts.getInitSegmentName();
            File tsInitSegmentFile = new File(mSaveDir, tsInitSegmentName);
            if (!tsInitSegmentFile.exists()) {
                downloadFile(ts, tsInitSegmentFile, ts.getInitSegmentUri());
            }
        }
        // AES-128：本地 playlist 会把 EXT-X-KEY 的 URI 改写成 local_<idx>.key，
        // 但此前从没人把这个 key 下下来——产物里的 key 永远缺失，ffmpeg 合并
        // 会因取不到密钥而失败（Java 拼接更是直接拒绝加密流）。
        if (ts.hasKey() && !TextUtils.isEmpty(ts.getKeyUri())) {
            File keyFile = new File(mSaveDir, ts.getLocalKeyUri());
            // 同一 key 被整条 playlist 的每个分片引用（1860 个分片共用一个 16 字节的
            // key 是常态）。按 URI 去重，避免同一文件被下载上千次；并发分片可能
            // 同时到达这里，所以用 putIfAbsent 抢占而不是先查后写。
            if (!isKeyFileUsable(keyFile)
                    && mDownloadedKeyUris.putIfAbsent(ts.getKeyUri(), Boolean.TRUE) == null) {
                try {
                    downloadKeyFileWithRetry(ts, keyFile);
                } catch (Exception e) {
                    // key 下载失败不再直接毁掉整个任务：站点常在 playlist 里声明
                    // AES-128 而实际给明文（17c.tw 实测），此时 key 往往已失效/403。
                    // 明文检测在完成分支才跑，若这里 throw 就永远到不了那一步，
                    // 内容明明可用却必然失败。
                    // 改为：记下失败，继续下分片；收尾时按「分片实际是否明文」决定
                    // 是放行（明文）还是报错（真加密但没 key）。
                    mDownloadedKeyUris.remove(ts.getKeyUri());
                    mKeyDownloadFailed = true;
                    LogUtils.w(DownloadConstants.TAG, "[M3U8_TS] key 下载失败（稍后按分片实际内容判定）: "
                            + ts.getKeyUri() + ", err=" + e.getMessage());
                }
            }
        }
        File tsFile = new File(mSaveDir, ts.getIndexName());
        if (!tsFile.exists()) {
            // ts is network resource, download ts file then rename it to local file.
            downloadFile(ts, tsFile, ts.getUrl());
        }

        if (tsFile.exists() && (tsFile.length() == ts.getContentLength())) {
            // rename network ts name to local file name.
            ts.setName(ts.getIndexName());
            ts.setTsSize(tsFile.length());
            notifyDownloadProgress();
        }
    }

    /**
     * 下载 AES-128 密钥文件并落盘为 {@link M3U8Seg#getLocalKeyUri()}。
     *
     * <p>密钥很小（通常 16 字节），但仍要带 {@code mHeaders}——部分站点的 key
     * 与分片同源同鉴权。
     */
    private void downloadKeyFileWithRetry(M3U8Seg ts, File keyFile) throws Exception {
        int retryCount = 0;
        while (true) {
            try {
                downloadKeyFile(ts, keyFile);
                return;
            } catch (Exception e) {
                // key 与分片同源，复用同一套重试判据（瞬时 503/断连不该毁掉整个任务）
                if (retryCount < HttpUtils.MAX_RETRY_COUNT && shouldRetryForException(e, retryCount)) {
                    retryCount++;
                    sleepBeforeRetry(retryCount);
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * 下载 AES-128 密钥并**原子落盘**。
     *
     * <p>先写 {@code .tmp} 再改名。直接写最终路径的话，网络中断会留下半截文件，
     * 而续传判据是 {@code keyFile.exists()} → 半截文件被当成「已下载」永久复用，
     * 任务报 SUCCESS 但产物用错 key 解出垃圾——「分片齐全但解不开」的真实入口。
     *
     * <p>同时校验长度：AES-128 的 key 必须是 16 字节。长度不符即视为失败，
     * 不留文件（有些 CDN 会把 403 页面/占位内容当 200 返回）。
     */
    private void downloadKeyFile(M3U8Seg ts, File keyFile) throws Exception {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        File tmpFile = new File(keyFile.getAbsolutePath() + ".tmp");
        try {
            connection = HttpUtils.getConnection(ts.getKeyUri(), mHeaders,
                    VideoDownloadUtils.getDownloadConfig().shouldIgnoreCertErrors());
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpUtils.RESPONSE_200 && responseCode != HttpUtils.RESPONSE_206) {
                throw new VideoDownloadException(DownloadExceptionUtils.VIDEO_REQUEST_FAILED);
            }
            inputStream = connection.getInputStream();
            File parent = keyFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            FileOutputStream fos = new FileOutputStream(tmpFile);
            long total = 0;
            try {
                byte[] buf = new byte[BUFFER_SIZE];
                int len;
                while ((len = inputStream.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    total += len;
                }
            } finally {
                VideoDownloadUtils.close(fos);
            }

            // AES-128 密钥固定 16 字节。长度不符说明拿到的不是 key
            // （403 页面、占位内容、被截断），必须当失败处理。
            if (!com.orange.downloader.utils.KeyFileValidator.isExpectedKeyLength(total)) {
                tmpFile.delete();
                throw new VideoDownloadException(DownloadExceptionUtils.VIDEO_REQUEST_FAILED);
            }
            // 原子改名：renameTo 失败时退化为复制，确保最终文件完整
            if (!tmpFile.renameTo(keyFile)) {
                java.io.FileOutputStream out = null;
                java.io.FileInputStream in = null;
                try {
                    in = new java.io.FileInputStream(tmpFile);
                    out = new java.io.FileOutputStream(keyFile);
                    byte[] buf = new byte[BUFFER_SIZE];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                } finally {
                    VideoDownloadUtils.close(in);
                    VideoDownloadUtils.close(out);
                    tmpFile.delete();
                }
            }
            LogUtils.i(DownloadConstants.TAG, "[M3U8_TS] key downloaded: " + keyFile.getName()
                    + ", size=" + keyFile.length());
        } catch (Exception e) {
            // 任何失败都不留半截文件，否则会被 exists() 判据误当成已下载
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            throw e;
        } finally {
            HttpUtils.closeConnection(connection);
            VideoDownloadUtils.close(inputStream);
        }
    }

    /**
     * key 文件是否可用：存在且长度正确（AES-128 = 16 字节）。
     * 长度不符的旧文件（此前半截写入留下的）会被删掉并返回 false，触发重下。
     */
    private boolean isKeyFileUsable(File keyFile) {
        long len = keyFile == null ? -1 : keyFile.length();
        boolean usable = com.orange.downloader.utils.KeyFileValidator.isUsable(keyFile);
        if (!usable && keyFile != null && keyFile.exists() == false && len > 0) {
            LogUtils.w(DownloadConstants.TAG, "[M3U8_TS] key 文件长度异常(" + len
                    + "B)，已删除并将重下: " + keyFile.getName());
        }
        return usable;
    }

    @Override
    public void resumeDownload() {
        initM3U8Ts();
        ensureKeysDownloaded();
        startDownload(mCurTs);
    }

    /**
     * 确保所有 key 都已下载。
     *
     * <p>为什么需要单独一趟：`hasKey` 在每个分片后复位，所以 remote.m3u8 里
     * `EXT-X-KEY` 只出现在 segment 0 之前。续传从 `mCurTs > 0` 开始，剩余分片
     * 全部 `hasKey == false` → 分片下载路径里的 key 分支**永不触发**。
     * 若 key 文件缺失（首次解析时被跳过、或用户清理过目录），产物 playlist
     * 会指向不存在的 `local_key_*.key`，合并失败。
     *
     * <p>这里遍历一次列表把所有出现过的 key URI 补下（去重），与分片下载
     * 共用 `mDownloadedKeyUris`，不会重复下载。
     */
    private void ensureKeysDownloaded() {
        if (mTsList == null || mTsList.isEmpty()) {
            return;
        }
        for (M3U8Seg ts : mTsList) {
            if (!ts.hasKey() || TextUtils.isEmpty(ts.getKeyUri())) {
                continue;
            }
            File keyFile = new File(mSaveDir, ts.getLocalKeyUri());
            if (isKeyFileUsable(keyFile)) {
                continue;
            }
            if (mDownloadedKeyUris.putIfAbsent(ts.getKeyUri(), Boolean.TRUE) != null) {
                continue;   // 本轮已处理过该 key
            }
            try {
                downloadKeyFileWithRetry(ts, keyFile);
            } catch (Exception e) {
                mDownloadedKeyUris.remove(ts.getKeyUri());
                mKeyDownloadFailed = true;
                LogUtils.w(DownloadConstants.TAG, "[M3U8_TS] 续传补下 key 失败: "
                        + ts.getKeyUri() + ", err=" + e.getMessage());
            }
        }
    }

    @Override
    public void pauseDownload() {
        if (mDownloadExecutor != null && !mDownloadExecutor.isShutdown()) {
            mDownloadExecutor.shutdownNow();
            notifyOnTaskPaused();
        }
    }

    private void notifyDownloadProgress() {
        updateM3U8TsInfo();
        if (mCurrentCachedSize == 0) {
            mCurrentCachedSize = VideoStorageUtils.countTotalSize(mSaveDir);
        }
        if (mTaskItem.isCompleted()) {
            mCurTs = mTotalTs;
            synchronized (mDownloadLock) {
                if (!mDownloadFinished) {
                    mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mCurrentCachedSize, mCurTs, mTotalTs, mSpeed);
                    mPercent = 100.0f;
                    mTotalSize = mCurrentCachedSize;
                    mDownloadTaskListener.onTaskFinished(mTotalSize);
                    mDownloadFinished = true;
                }
            }
            return;
        }
        if (mCurTs >= mTotalTs) {
            mCurTs = mTotalTs;
        }
        synchronized (mDownloadLock) {
            float percent = mCurTs * 1.0f * 100 / mTotalTs;
            if (!VideoDownloadUtils.isFloatEqual(percent, mPercent)) {
                long nowTime = System.currentTimeMillis();
                if (mCurrentCachedSize > mLastCachedSize && nowTime > mLastInvokeTime) {
                    mSpeed = (mCurrentCachedSize - mLastCachedSize) * 1000 * 1.0f / (nowTime - mLastInvokeTime);
                }
                if (!mDownloadFinished) {
                    mDownloadTaskListener.onTaskProgressForM3U8(percent, mCurrentCachedSize, mCurTs, mTotalTs, mSpeed);
                }
                mPercent = percent;
                mLastCachedSize = mCurrentCachedSize;
                mLastInvokeTime = nowTime;
            }
        }
        boolean isCompleted = true;
        for (M3U8Seg ts : mTsList) {
            File tsFile = new File(mSaveDir, ts.getIndexName());
            if (!tsFile.exists()) {
                isCompleted = false;
                break;
            }
        }
        if (isCompleted) {
            LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] All TS segments completed, creating local m3u8 files");
            try {
                // 站点常在 playlist 里声明 AES-128，实际却直接给明文 TS
                // （17c.tw 实测：分片每个 188 字节边界都是 0x47 同步字节，
                //  ffmpeg 按声明解密反而把明文解成垃圾 → 合并必然失败）。
                // 声明不可信，按**实际下载到的字节**决定本地 playlist 怎么写。
                mSegmentsArePlainText = detectSegmentsArePlainText();
                if (mSegmentsArePlainText) {
                    LogUtils.w(DownloadConstants.TAG,
                            "[M3U8_TASK] Playlist declares encryption but segments are plain TS; "
                                    + "writing plaintext local playlist");
                }
                // 真加密但 key 没下到 → 产物必然解不开，此时才报错（明确失败，
                // 不静默产出坏文件）。明文场景放行（key 下载失败无关紧要）。
                if (mKeyDownloadFailed && !mSegmentsArePlainText) {
                    throw new VideoDownloadException(DownloadExceptionUtils.VIDEO_REQUEST_FAILED);
                }
                createLocalM3U8File();
                createLocalM3U8FileWithKey();
                LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] Local m3u8 files created successfully");
            } catch (Exception e) {
                LogUtils.e(DownloadConstants.TAG, "[M3U8_TASK] Failed to create local m3u8: " + e.getMessage());
                notifyDownloadError(e);
                return;
            }
            synchronized (mDownloadLock) {
                if (!mDownloadFinished) {
                    mTotalSize = mCurrentCachedSize;
                    mDownloadTaskListener.onTaskProgressForM3U8(100.0f, mCurrentCachedSize, mCurTs, mTotalTs, mSpeed);
                    mDownloadTaskListener.onTaskFinished(mTotalSize);
                    mDownloadFinished = true;
                }
            }
        }
    }

    private void updateM3U8TsInfo() {
        long tempCurrentCachedSize = 0;
        int tempCurTs = 0;
        for (M3U8Seg ts : mTsList) {
            File tempTsFile = new File(mSaveDir, ts.getIndexName());
            if (tempTsFile.exists() && tempTsFile.length() > 0) {
                ts.setTsSize(tempTsFile.length());
                tempCurTs++;
            }
        }
        mCurTs = tempCurTs;
        mCurrentCachedSize = tempCurrentCachedSize;
    }

    private void notifyDownloadFinish() {
        notifyDownloadProgress();
        notifyDownloadFinish(mTotalSize);
    }

    private void notifyDownloadFinish(long size) {
        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] notifyDownloadFinish called, size=" + size + ", isCompleted=" + mTaskItem.isCompleted());
        if (mTaskItem.isCompleted()) {
            synchronized (mDownloadLock) {
                if (!mDownloadFinished) {
                    // 确保filePath正确设置
                    String localM3U8Path = mSaveDir.getAbsolutePath() + File.separator + mSaveName + "_" + VideoDownloadUtils.LOCAL_M3U8;
                    mTaskItem.setFilePath(localM3U8Path);
                    mTaskItem.setFileName(mSaveName + "_" + VideoDownloadUtils.LOCAL_M3U8);
                    LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] Setting filePath: " + localM3U8Path);
                    
                    // 验证文件是否存在
                    File m3u8File = new File(localM3U8Path);
                    if (m3u8File.exists()) {
                        LogUtils.i(DownloadConstants.TAG, "[M3U8_TASK] Local m3u8 file exists, size=" + m3u8File.length());
                    } else {
                        LogUtils.e(DownloadConstants.TAG, "[M3U8_TASK] Local m3u8 file NOT exists: " + localM3U8Path);
                    }
                    
                    mDownloadTaskListener.onTaskFinished(size);
                    mDownloadFinished = true;
                }
            }
        } else {
            LogUtils.w(DownloadConstants.TAG, "[M3U8_TASK] notifyDownloadFinish called but task not marked as completed");
        }
    }

    private void notifyDownloadError(Exception e) {
        notifyOnTaskFailed(e);
    }

    public void downloadFile(M3U8Seg ts, File file, String videoUrl) throws Exception {
        int retryCount = ts.getRetryCount();
        while (true) {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            try {
                connection = HttpUtils.getConnection(videoUrl, mHeaders, VideoDownloadUtils.getDownloadConfig().shouldIgnoreCertErrors());
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpUtils.RESPONSE_200 || responseCode == HttpUtils.RESPONSE_206) {
                    ts.setRetryCount(0);
                    mContinuousSuccessTsCount++;
                    if (mContinuousSuccessTsCount > CONTINUOUS_SUCCESS_TS_THRESHOLD && mM3U8DownloadPoolCount < THREAD_COUNT) {
                        mM3U8DownloadPoolCount += 1;
                        mContinuousSuccessTsCount -= 1;
                        setThreadPoolArgument(mM3U8DownloadPoolCount, mM3U8DownloadPoolCount);
                    }
                    inputStream = connection.getInputStream();
                    long contentLength = connection.getContentLength();
                    saveFile(inputStream, file, contentLength, ts, videoUrl);
                    return;
                }
                mContinuousSuccessTsCount = 0;
                if (shouldRetryForResponseCode(responseCode, retryCount)) {
                    retryCount++;
                    ts.setRetryCount(retryCount);
                    reduceDownloadPoolForRetry();
                    sleepBeforeRetry(retryCount);
                    continue;
                }
                throw new VideoDownloadException(DownloadExceptionUtils.VIDEO_REQUEST_FAILED);
            } catch (Exception e) {
                mContinuousSuccessTsCount = 0;
                if (shouldRetryForException(e, retryCount)) {
                    retryCount++;
                    ts.setRetryCount(retryCount);
                    reduceDownloadPoolForRetry();
                    sleepBeforeRetry(retryCount);
                    continue;
                }
                LogUtils.w(DownloadConstants.TAG, "downloadFile failed, exception=" + e.getMessage());
                throw e;
            } finally {
                HttpUtils.closeConnection(connection);
                VideoDownloadUtils.close(inputStream);
            }
        }
    }

    private void saveFile(InputStream inputStream, File file, long contentLength, M3U8Seg ts, String videoUrl) throws Exception {
        FileOutputStream fos = null;
        long totalLength = 0;
        try {
            // 检查文件是否已存在且完整
            if (file.exists()) {
                long existingLen = file.length();
                if (existingLen > 0 && (contentLength <= 0 || existingLen == contentLength)) {
                    ts.setContentLength(existingLen);
                    LogUtils.i(DownloadConstants.TAG, "[M3U8_TS] File already exists and complete: " + file.getName() + ", size=" + existingLen);
                    return;
                } else if (existingLen == 0) {
                    file.delete();
                    LogUtils.i(DownloadConstants.TAG, "[M3U8_TS] Deleted zero-length file: " + file.getName());
                }
            }
            
            // 确保父目录存在
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            // 使用原子操作创建文件，避免并发冲突
            // createNewFile返回false可能是因为文件已存在（并发情况）
            boolean created = file.createNewFile();
            if (!created) {
                // 文件可能已被其他线程创建，检查是否存在
                if (!file.exists()) {
                    // 文件确实不存在，尝试再次创建
                    try {
                        created = file.createNewFile();
                        if (!created && !file.exists()) {
                            throw new IOException("Failed to create file after retry: " + file.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        // 如果仍然失败，检查是否是并发创建导致
                        if (!file.exists()) {
                            throw new IOException("Failed to create file: " + file.getAbsolutePath(), e);
                        }
                    }
                }
                // 文件已存在（并发创建），继续使用
                LogUtils.i(DownloadConstants.TAG, "[M3U8_TS] File already created by another thread: " + file.getName());
            }
            
            fos = new FileOutputStream(file);
            int len;
            byte[] buf = new byte[BUFFER_SIZE];
            while ((len = inputStream.read(buf)) != -1) {
                totalLength += (long)len;
                fos.write(buf, 0, len);
            }
            if (contentLength > 0 && contentLength == totalLength) {
                ts.setContentLength(contentLength);
            } else {
                ts.setContentLength(totalLength);
            }
        } catch (IOException e) {
            if (file.exists() && ((contentLength > 0 && contentLength == file.length()) || (contentLength == -1 && totalLength == file.length()))) {
                //这时候也能说明ts已经下载好了
            } else {
                if ((e instanceof ProtocolException &&
                        !TextUtils.isEmpty(e.getMessage()) &&
                        e.getMessage().contains(DownloadExceptionUtils.PROTOCOL_UNEXPECTED_END_OF_STREAM)) &&
                        (contentLength > totalLength && totalLength == file.length())) {
                    if (file.length() == 0) {
                        ts.setRetryCount(ts.getRetryCount() + 1);
                        if (ts.getRetryCount() < HttpUtils.MAX_RETRY_COUNT) {
                            sleepBeforeRetry(ts.getRetryCount());
                            downloadFile(ts, file, videoUrl);
                        } else {
                            LogUtils.w(DownloadConstants.TAG, file.getAbsolutePath() + ", length=" + file.length() + ", saveFile failed, exception=" + e);
                            if (file.exists()) {
                                file.delete();
                            }
                            throw e;
                        }
                    } else {
                        ts.setContentLength(totalLength);
                    }
                } else {
                    LogUtils.w(DownloadConstants.TAG, file.getAbsolutePath() + ", length=" + file.length() + ", saveFile failed, exception=" + e);
                    if (file.exists()) {
                        file.delete();
                    }
                    throw e;
                }
            }
        } finally {
            VideoDownloadUtils.close(inputStream);
            VideoDownloadUtils.close(fos);
        }
    }

    /**
     * 检测已下载的分片是否其实是**明文** TS。
     *
     * <p>背景：有站点在 playlist 里写 {@code #EXT-X-KEY:METHOD=AES-128}，但分片本身
     * 是明文（17c.tw 实测：分片每个 188 字节边界都命中 0x47 同步字节）。
     * 此时若照声明保留 EXT-X-KEY，ffmpeg 会把明文当密文解，得到垃圾数据，
     * 合并必然失败——而且失败信息（"Error when loading first segment"）完全
     * 指不出真实原因。这里按实际字节判定，让本地 playlist 反映真相。
     *
     * <p>判定依据：MPEG-TS 是 188 字节定长包，每个包首字节必为 0x47。
     * 密文流不会有这种规律（0x47 只会随机出现，概率约 1/256）。
     *
     * @return true 表示分片是明文，本地 playlist 不应保留 EXT-X-KEY
     */
    private boolean detectSegmentsArePlainText() {
        if (mTsList == null || mTsList.isEmpty()) {
            return false;
        }
        // 抽查前几个分片即可；只要有一个确认是明文就够（同一来源的分片加密状态一致）
        int checked = 0;
        for (M3U8Seg ts : mTsList) {
            File f = new File(mSaveDir, ts.getIndexName());
            if (!f.exists() || f.length() < 188 * 4) {
                continue;
            }
            checked++;
            if (isPlainTsFile(f)) {
                LogUtils.i(DownloadConstants.TAG,
                        "[M3U8_TASK] segment " + f.getName() + " looks like plain TS");
                return true;
            }
            if (checked >= 3) {
                break;
            }
        }
        return false;
    }

    /**
     * 按 MPEG-TS 的 188 字节定长包结构判断文件是否为明文 TS。
     * 实现见 {@link com.orange.downloader.utils.TsProbe}。
     */
    private boolean isPlainTsFile(File file) {
        return com.orange.downloader.utils.TsProbe.isPlainTsFile(file);
    }

    private void createLocalM3U8File() throws IOException {
        synchronized (mFileLock) {
            File tempM3U8File = new File(mSaveDir, "temp.m3u8");
            if (tempM3U8File.exists()) {
                tempM3U8File.delete();
            }

            BufferedWriter bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
            bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
            bfw.write(M3U8Constants.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
            bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getInitSequence() + "\n");

            bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

            for (M3U8Seg m3u8Ts : mTsList) {
                if (m3u8Ts.hasInitSegment()) {
                    String initSegmentInfo;
                    String initSegmentFilePath = buildLocalPlaylistPath(m3u8Ts.getInitSegmentName());
                    if (m3u8Ts.getSegmentByteRange() != null) {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath + "\"" + ",BYTERANGE=\"" + m3u8Ts.getSegmentByteRange() + "\"";
                    } else {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath  + "\"";
                    }
                    bfw.write(M3U8Constants.TAG_INIT_SEGMENT + ":" + initSegmentInfo + "\n");
                }
                // 分片实际是明文时不要保留 EXT-X-KEY：否则 ffmpeg 会把明文当密文解
                if (m3u8Ts.hasKey() && !mSegmentsArePlainText) {
                    if (m3u8Ts.getMethod() != null) {
                        String key = "METHOD=" + m3u8Ts.getMethod();
                        if (m3u8Ts.getKeyUri() != null) {
                            key += ",URI=\"" + buildLocalPlaylistPath(m3u8Ts.getLocalKeyUri()) + "\"";
                        }
                        if (m3u8Ts.getKeyIV() != null) {
                            key += ",IV=" + m3u8Ts.getKeyIV();
                        }
                        bfw.write(M3U8Constants.TAG_KEY + ":" + key + "\n");
                    }
                }
                if (m3u8Ts.hasDiscontinuity()) {
                    bfw.write(M3U8Constants.TAG_DISCONTINUITY + "\n");
                }
                bfw.write(M3U8Constants.TAG_MEDIA_DURATION + ":" + m3u8Ts.getDuration() + ",\n");
                String byteRange = m3u8Ts.getByteRange();
                if (!TextUtils.isEmpty(byteRange)) {
                    bfw.write(M3U8Constants.TAG_BYTERANGE + ":" + byteRange + "\n");
                }
                bfw.write(buildLocalPlaylistPath(m3u8Ts.getIndexName()));
                bfw.newLine();
            }
            bfw.write(M3U8Constants.TAG_ENDLIST);
            bfw.flush();
            bfw.close();

            File localM3U8File = new File(mSaveDir, mSaveName + "_" + VideoDownloadUtils.LOCAL_M3U8);
            if (localM3U8File.exists()) {
                localM3U8File.delete();
            }
            tempM3U8File.renameTo(localM3U8File);
        }
    }

    private void createLocalM3U8FileWithKey() throws IOException {
        synchronized (mFileLock) {
            File tempM3U8File = new File(mSaveDir, "temp.m3u8");
            if (tempM3U8File.exists()) {
                tempM3U8File.delete();
            }

            BufferedWriter bfw = new BufferedWriter(new FileWriter(tempM3U8File, false));
            bfw.write(M3U8Constants.PLAYLIST_HEADER + "\n");
            bfw.write(M3U8Constants.TAG_VERSION + ":" + mM3U8.getVersion() + "\n");
            bfw.write(M3U8Constants.TAG_MEDIA_SEQUENCE + ":" + mM3U8.getInitSequence() + "\n");

            bfw.write(M3U8Constants.TAG_TARGET_DURATION + ":" + mM3U8.getTargetDuration() + "\n");

            for (M3U8Seg m3u8Ts : mTsList) {
                if (m3u8Ts.hasInitSegment()) {
                    String initSegmentInfo;
                    String initSegmentFilePath = buildLocalPlaylistPath(m3u8Ts.getInitSegmentName());
                    if (m3u8Ts.getSegmentByteRange() != null) {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath + "\"" + ",BYTERANGE=\"" + m3u8Ts.getSegmentByteRange() + "\"";
                    } else {
                        initSegmentInfo = "URI=\"" + initSegmentFilePath  + "\"";
                    }
                    bfw.write(M3U8Constants.TAG_INIT_SEGMENT + ":" + initSegmentInfo + "\n");
                }
                // 同上：分片实际是明文时不要保留 EXT-X-KEY
                if (m3u8Ts.hasKey() && !mSegmentsArePlainText) {
                    if (m3u8Ts.getMethod() != null) {
                        String key = "METHOD=" + m3u8Ts.getMethod();
                        if (m3u8Ts.getKeyUri() != null) {
                            key += ",URI=\"" + m3u8Ts.getKeyUri() + "\"";
                        }
                        if (m3u8Ts.getKeyIV() != null) {
                            key += ",IV=" + m3u8Ts.getKeyIV();
                        }
                        bfw.write(M3U8Constants.TAG_KEY + ":" + key + "\n");
                    }
                }
                if (m3u8Ts.hasDiscontinuity()) {
                    bfw.write(M3U8Constants.TAG_DISCONTINUITY + "\n");
                }
                bfw.write(M3U8Constants.TAG_MEDIA_DURATION + ":" + m3u8Ts.getDuration() + ",\n");
                String byteRange = m3u8Ts.getByteRange();
                if (!TextUtils.isEmpty(byteRange)) {
                    bfw.write(M3U8Constants.TAG_BYTERANGE + ":" + byteRange + "\n");
                }
                bfw.write(buildLocalPlaylistPath(m3u8Ts.getIndexName()));
                bfw.newLine();
            }
            bfw.write(M3U8Constants.TAG_ENDLIST);
            bfw.flush();
            bfw.close();

            File localM3U8File = new File(mSaveDir, mSaveName + "_" + VideoDownloadUtils.LOCAL_M3U8_WITH_KEY);
            if (localM3U8File.exists()) {
                localM3U8File.delete();
            }
            tempM3U8File.renameTo(localM3U8File);
        }
    }

    private String buildLocalPlaylistPath(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        return fileName.replace("\\", "/");
    }

    private void sleepBeforeRetry(int retryCount) {
        if (retryCount <= 0) {
            return;
        }
        long sleepMs = Math.min(8000, 500L * retryCount);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldRetryForResponseCode(int responseCode, int retryCount) {
        if (retryCount >= HttpUtils.MAX_RETRY_COUNT) {
            return false;
        }
        return responseCode == HttpUtils.RESPONSE_503
                || responseCode == 408
                || responseCode == 429
                || (responseCode >= 500 && responseCode < 600);
    }

    private boolean shouldRetryForException(Exception e, int retryCount) {
        if (retryCount >= HttpUtils.MAX_RETRY_COUNT) {
            return false;
        }
        if (e instanceof ProtocolException && !TextUtils.isEmpty(e.getMessage())
                && e.getMessage().contains(DownloadExceptionUtils.PROTOCOL_UNEXPECTED_END_OF_STREAM)) {
            return true;
        }
        return e instanceof IOException;
    }

    private void reduceDownloadPoolForRetry() {
        if (mM3U8DownloadPoolCount > 1) {
            mM3U8DownloadPoolCount -= 1;
            setThreadPoolArgument(mM3U8DownloadPoolCount, mM3U8DownloadPoolCount);
        }
    }
}
