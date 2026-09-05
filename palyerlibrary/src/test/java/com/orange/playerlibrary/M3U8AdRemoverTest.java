package com.orange.playerlibrary;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class M3U8AdRemoverTest {

    @Test
    public void 精确DISCONTINUITY标签可识别() {
        assertTrue(M3U8AdRemover.containsDiscontinuity(
                "#EXTM3U\n#EXTINF:5,\nfirst.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:5,\nsecond.ts\n"));
        assertTrue(M3U8AdRemover.containsDiscontinuity(
                "#EXTM3U\r\n  #EXT-X-DISCONTINUITY  \r\nsegment.ts\r\n"));
    }

    @Test
    public void 相似文本不会误判() {
        assertFalse(M3U8AdRemover.containsDiscontinuity(
                "#EXTM3U\n# comment #EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY-SEQUENCE:3\nsegment.ts\n"));
    }

    @Test
    public void 空内容不包含DISCONTINUITY() {
        assertFalse(M3U8AdRemover.containsDiscontinuity(null));
        assertFalse(M3U8AdRemover.containsDiscontinuity(""));
    }
}
