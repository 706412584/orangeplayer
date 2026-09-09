package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class TranslationCacheStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void saveAndLoadRoundTrip() throws Exception {
        File dir = tmp.newFolder("cache");
        TranslationCacheStore store = new TranslationCacheStore(dir, "video1|zh|en|deepseek-chat");

        String[] translations = new String[4];
        translations[0] = "你好";
        translations[1] = "有换行\n的第二句";
        translations[3] = "反斜杠\\和换行\n混合";
        Glossary g = new Glossary();
        g.put("Naruto", "鸣人");

        store.save(translations, g);

        String[] loaded = store.load();
        assertEquals(4, loaded.length);
        assertEquals("你好", loaded[0]);
        assertEquals("有换行\n的第二句", loaded[1]);
        assertNull(loaded[2]);   // 未译行保持 null
        assertEquals("反斜杠\\和换行\n混合", loaded[3]);

        Glossary loadedG = store.loadGlossary();
        assertEquals(1, loadedG.size());
        assertEquals("鸣人", loadedG.get("Naruto"));
    }

    @Test
    public void differentKeysAreIsolated() throws Exception {
        File dir = tmp.newFolder("cache");
        TranslationCacheStore a = new TranslationCacheStore(dir, "videoA");
        TranslationCacheStore b = new TranslationCacheStore(dir, "videoB");

        a.save(new String[]{"译文"}, null);
        assertTrue(a.exists());
        assertFalse(b.exists());
        assertNull(b.load());
    }

    @Test
    public void noFileReturnsNullAndEmptyGlossary() throws Exception {
        File dir = tmp.newFolder("cache");
        TranslationCacheStore store = new TranslationCacheStore(dir, "nothing");
        assertNull(store.load());
        assertTrue(store.loadGlossary().isEmpty());
        assertFalse(store.exists());
    }

    @Test
    public void escapeUnescape() {
        assertEquals("a\\\\n", TranslationCacheStore.escape("a\\n"));
        assertEquals("a\nb", TranslationCacheStore.unescape("a\\nb"));
        assertEquals("a\\b", TranslationCacheStore.unescape("a\\\\b"));
        assertEquals("plain", TranslationCacheStore.escape("plain"));
        assertEquals("plain", TranslationCacheStore.unescape("plain"));
    }
}
