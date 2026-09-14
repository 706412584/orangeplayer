package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * 监听器快照的回归测试。
 *
 * <p>锁住的契约：分发方遍历快照，所以监听器在回调内解绑自己不会让遍历崩溃。
 * 修复前的实现直接 for-each 遍历原列表，这条测试会抛
 * {@link java.util.ConcurrentModificationException}。
 *
 * <p>真机来源：Android 16 / arm64，曾进入画中画 → 重启 App → 点播放，
 * {@code OrangevideoView.notifyPlayStateChanged} 派发 STATE_PREPARED 时崩溃。
 */
public class ListenerSnapshotTest {

    private interface Listener {
        void onEvent();
    }

    /** 模拟原实现：直接遍历传入列表。用于证明这条测试确实能捕获该缺陷。 */
    private static void dispatchWithoutSnapshot(List<Listener> listeners, List<Listener> toRemove) {
        for (Listener l : listeners) {
            l.onEvent();
            if (toRemove != null && toRemove.contains(l)) {
                listeners.remove(l);
            }
        }
    }

    /** 修复后的实现：遍历快照。 */
    private static void dispatchWithSnapshot(List<Listener> listeners, List<Listener> toRemove) {
        for (Listener l : ListenerSnapshot.of(listeners)) {
            l.onEvent();
            if (toRemove != null && toRemove.contains(l)) {
                listeners.remove(l);
            }
        }
    }

    /**
     * 核心契约：监听器在回调内解绑自己时，分发不抛异常。
     * 这正是 PiP 恢复监听器的写法（onPlayStateChanged 里 removeOnStateChangeListener(this)）。
     *
     * <p>复刻真机场景：demo 先注册 2 个嗅探监听器，再注册 PiP 恢复监听器
     * （MainActivity 的 initPlayer → setupSniffingListener，之后 initPiPHelper），
     * 所以自解绑的是**最后一个**元素。这一点很关键：{@code ArrayList.Itr.hasNext()}
     * 判的是 {@code cursor != size}，若自解绑的是中间元素则 cursor 恰好等于新的 size，
     * 循环直接结束而不会暴露缺陷。
     */
    @Test
    public void 回调内自解绑_快照分发不抛异常() {
        List<Listener> listeners = new ArrayList<>();
        AtomicInteger fired = new AtomicInteger();

        Listener sniffing = fired::incrementAndGet;
        Listener sniffingResult = fired::incrementAndGet;
        Listener selfRemoving = fired::incrementAndGet;   // 最后注册 = PiP 恢复监听器

        listeners.add(sniffing);
        listeners.add(sniffingResult);
        listeners.add(selfRemoving);

        // selfRemoving 在回调内解绑自己——修复前这里抛 ConcurrentModificationException
        dispatchWithSnapshot(listeners, java.util.Collections.singletonList(selfRemoving));

        assertEquals("三个监听器都应在本次通知中收到回调", 3, fired.get());
        assertEquals("自解绑的监听器应从原列表移除", 2, listeners.size());
        assertFalse(listeners.contains(selfRemoving));
    }

    /**
     * 对照组：证明上面的测试确实针对该缺陷——同样的场景用旧实现会抛 CME。
     * 若此测试不再抛异常，说明测试本身失去了检出能力。
     */
    @Test
    public void 对照_旧实现遍历原列表会抛并发修改异常() {
        List<Listener> listeners = new ArrayList<>();
        Listener selfRemoving = () -> { };

        listeners.add(() -> { });
        listeners.add(() -> { });
        listeners.add(selfRemoving);   // 最后注册，与真机一致

        boolean threw = false;
        try {
            dispatchWithoutSnapshot(listeners, java.util.Collections.singletonList(selfRemoving));
        } catch (java.util.ConcurrentModificationException e) {
            threw = true;
        }
        assertTrue("旧实现应抛 ConcurrentModificationException（否则本组测试失去检出能力）", threw);
    }

    /**
     * 边界：自解绑的是中间元素时，旧实现因 cursor==size 提前结束而不抛异常。
     * 记录这个差异，避免有人误以为"删中间元素也是安全的"而放松快照要求。
     */
    @Test
    public void 中间元素自解绑_旧实现不抛但快照实现语义一致() {
        List<Listener> listeners = new ArrayList<>();
        AtomicInteger fired = new AtomicInteger();
        Listener middle = fired::incrementAndGet;
        listeners.add(fired::incrementAndGet);
        listeners.add(middle);
        listeners.add(fired::incrementAndGet);

        dispatchWithSnapshot(listeners, java.util.Collections.singletonList(middle));

        assertEquals("中间元素自解绑时，后面的监听器仍应收到回调", 3, fired.get());
        assertEquals(2, listeners.size());
    }

    /** 快照是拷贝：调用后修改原列表不影响已取得的快照。 */
    @Test
    public void 快照与源列表解耦() {
        List<Listener> source = new ArrayList<>();
        Listener a = () -> { };
        source.add(a);

        List<Listener> snapshot = ListenerSnapshot.of(source);
        assertNotSame(source, snapshot);
        assertEquals(1, snapshot.size());

        source.add(() -> { });
        assertEquals("快照不应随源列表增长", 1, snapshot.size());
        assertEquals(2, source.size());

        source.clear();
        assertEquals("快照不应随源列表清空", 1, snapshot.size());
    }

    /** null / 空列表返回空列表而非 null，调用方无需判空。 */
    @Test
    public void null与空列表_返回空列表() {
        assertTrue(ListenerSnapshot.of(null).isEmpty());
        assertTrue(ListenerSnapshot.of(new ArrayList<Listener>()).isEmpty());
    }

    /** 遍历期间新增的监听器不参与本次通知（快照语义）。 */
    @Test
    public void 遍历中新增的监听器_不参与本次通知() {
        List<Listener> listeners = new ArrayList<>();
        AtomicInteger fired = new AtomicInteger();
        List<Listener> latecomer = new ArrayList<>();

        Listener adder = () -> {
            fired.incrementAndGet();
            Listener added = fired::incrementAndGet;
            listeners.add(added);
            latecomer.add(added);
        };
        listeners.add(adder);

        dispatchWithSnapshot(listeners, null);

        assertEquals("新增的监听器不应在本次通知中触发", 1, fired.get());
        assertEquals("但它应已在列表中，供下次通知使用", 2, listeners.size());
    }
}
