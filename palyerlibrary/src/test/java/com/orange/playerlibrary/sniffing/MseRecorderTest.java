package com.orange.playerlibrary.sniffing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * MSE 录制器单元测试。
 *
 * <p>核心契约：fMP4 分片按到达顺序**原样追加**，不做任何重组——
 * init 段（ftyp+moov）在前、媒体段（moof+mdat）在后，拼接结果即标准
 * fragmented MP4。这一点若被破坏（例如按 mime 归并不同 SourceBuffer），
 * 产物会静默损坏，所以用测试锁住。
 *
 * <p>走 {@code onChunkBytes}（字节入口）而不是 {@code onChunk}（base64 入口）：
 * base64 只是 JS 桥的传输细节，核心逻辑不该被它拖进 Android 依赖。
 */
public class MseRecorderTest {

    private File mDir;
    private MseRecorder mRecorder;

    @Before
    public void setUp() throws Exception {
        mDir = Files.createTempDirectory("mse-rec-test").toFile();
        mRecorder = new MseRecorder(mDir);
        mRecorder.start();
    }

    @After
    public void tearDown() {
        if (mRecorder != null) {
            mRecorder.stop();
        }
    }

    private static byte[] read(File f) throws Exception {
        return Files.readAllBytes(f.toPath());
    }

    /** init 段与媒体段按顺序拼接，且字节完全一致 */
    @Test
    public void 分片按顺序追加_字节与拼接结果一致() throws Exception {
        byte[] init = {0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70};   // ftyp
        byte[] media1 = {0x00, 0x00, 0x01, 0x00, 0x6d, 0x6f, 0x6f, 0x66}; // moof
        byte[] media2 = {0x00, 0x00, 0x00, 0x10, 0x6d, 0x64, 0x61, 0x74}; // mdat

        mRecorder.onChunkBytes("sb1", "video/mp4", init, true);
        mRecorder.onChunkBytes("sb1", "video/mp4", media1, false);
        mRecorder.onChunkBytes("sb1", "video/mp4", media2, false);

        List<File> files = mRecorder.stop();
        assertEquals(1, files.size());

        byte[] expected = new byte[init.length + media1.length + media2.length];
        System.arraycopy(init, 0, expected, 0, init.length);
        System.arraycopy(media1, 0, expected, init.length, media1.length);
        System.arraycopy(media2, 0, expected, init.length + media1.length, media2.length);

        assertArrayEquals("产物必须是各分片原样按序拼接", expected, read(files.get(0)));
    }

    /**
     * 两个同类 SourceBuffer（换集/换码率）必须落成**两个**文件。
     * 回归：若按 mime 归并，两段视频会交叉写入同一文件 → 产物损坏。
     */
    @Test
    public void 同类SourceBuffer应分文件_不按mime归并() throws Exception {
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{1, 1}, true);
        mRecorder.onChunkBytes("sb2", "video/mp4", new byte[]{2, 2}, true);
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{3}, false);
        mRecorder.onChunkBytes("sb2", "video/mp4", new byte[]{4}, false);

        List<File> files = mRecorder.stop();
        assertEquals("两个 SourceBuffer 应产出两个文件", 2, files.size());

        // 每个文件内部必须是连续的同源数据，不能混入另一路
        boolean sawA = false;
        boolean sawB = false;
        for (File f : files) {
            byte[] d = read(f);
            if (d.length == 3 && d[0] == 1 && d[1] == 1 && d[2] == 3) {
                sawA = true;
            }
            if (d.length == 3 && d[0] == 2 && d[1] == 2 && d[2] == 4) {
                sawB = true;
            }
        }
        assertTrue("应存在 sb1 的独立文件且数据连续", sawA);
        assertTrue("应存在 sb2 的独立文件且数据连续", sawB);
    }

    /** 音视频分轨：video 与 audio 各自成文件 */
    @Test
    public void 音视频分轨() throws Exception {
        mRecorder.onChunkBytes("sbv", "video/mp4", new byte[]{0x11, 0x11}, true);
        mRecorder.onChunkBytes("sba", "audio/mp4", new byte[]{0x22}, true);
        mRecorder.onChunkBytes("sbv", "video/mp4", new byte[]{0x33}, false);

        List<File> files = mRecorder.stop();
        assertEquals(2, files.size());
        boolean hasMp4 = false;
        boolean hasM4a = false;
        for (File f : files) {
            if (f.getName().endsWith(".mp4")) hasMp4 = true;
            if (f.getName().endsWith(".m4a")) hasM4a = true;
        }
        assertTrue("应有视频文件", hasMp4);
        assertTrue("应有音频文件", hasM4a);
    }

    /** 未 start 时不应接收数据 */
    @Test
    public void 未开始录制时不接收数据() throws Exception {
        MseRecorder idle = new MseRecorder(mDir);
        idle.onChunkBytes("sb1", "video/mp4", new byte[]{1}, true);
        assertTrue("未 start 不应产出文件", idle.stop().isEmpty());
        assertEquals(0, idle.getTotalBytes());
    }

    /** 空分片不应产生文件，也不应抛异常 */
    @Test
    public void 空分片被忽略() throws Exception {
        mRecorder.onChunkBytes("sb1", "video/mp4", null, true);
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[0], true);
        assertEquals(0, mRecorder.getTotalBytes());
        assertTrue(mRecorder.stop().isEmpty());
    }

    /** stop 之后不再接收（避免录制结束仍有在途分片写进来） */
    @Test
    public void stop后不再接收数据() throws Exception {
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{1, 2}, true);
        List<File> first = mRecorder.stop();
        assertEquals(1, first.size());
        long sizeAfterStop = first.get(0).length();

        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{9, 9}, false);
        assertEquals("stop 后不应再增长", sizeAfterStop, first.get(0).length());
        assertFalse(mRecorder.isRecording());
    }

    /** isRecording 与字节/轨道统计 */
    @Test
    public void 录制状态与字节统计() {
        assertTrue(mRecorder.isRecording());
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{1, 2, 3}, true);
        assertEquals(3, mRecorder.getTotalBytes());
        assertEquals(1, mRecorder.getTrackCount());
        mRecorder.stop();
        assertFalse(mRecorder.isRecording());
    }

    /** 没有数据的分片不应留下空文件 */
    @Test
    public void 无数据不留空文件() throws Exception {
        mRecorder.onChunkBytes("sb1", "video/mp4", new byte[]{1}, true);
        List<File> files = mRecorder.stop();
        assertEquals(1, files.size());
        for (File f : files) {
            assertTrue("产物不应为空文件", f.length() > 0);
        }
    }
}
