package com.orange.player.mpv.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class S4EventAdapterTest {

    @Test
    public void prepareWaitsForSurfaceBeforeLoadingAndNotifyingPrepared() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingListener listener = new RecordingListener();
        S4EventAdapter adapter = new S4EventAdapter(bridge, listener);

        adapter.prepareAsync("https://example.test/video.mp4");
        assertTrue(bridge.calls.isEmpty());

        adapter.setSurface(new Object());
        assertEquals("attach", bridge.calls.get(0));
        assertEquals("load:https://example.test/video.mp4", bridge.calls.get(1));
        assertEquals(0, listener.preparedCount);

        adapter.onFileLoaded();
        assertEquals(1, listener.preparedCount);
    }

    @Test
    public void positionChangesOnlyWhenAnObservedValueArrives() {
        S4EventAdapter adapter = new S4EventAdapter(
                new RecordingBridge(), new RecordingListener());

        adapter.start();
        adapter.onTimePosition(1.25d);
        assertEquals(1250L, adapter.getCurrentPositionMs());

        adapter.pause();
        assertEquals(1250L, adapter.getCurrentPositionMs());
        assertFalse(adapter.isPlaying());

        adapter.start();
        adapter.onTimePosition(3.75d);
        assertEquals(3750L, adapter.getCurrentPositionMs());
    }

    @Test
    public void endFileErrorDoesNotReportCompletion() {
        RecordingListener listener = new RecordingListener();
        S4EventAdapter adapter = new S4EventAdapter(new RecordingBridge(), listener);

        adapter.onEndFile(S4EventAdapter.EndReason.ERROR, -13);

        assertEquals(1, listener.errors.size());
        assertEquals(Integer.valueOf(-13), listener.errors.get(0));
        assertEquals(0, listener.completionCount);
    }

    @Test
    public void surfaceRecreationDoesNotReloadOrDropSeekCompletion() {
        RecordingBridge bridge = new RecordingBridge();
        RecordingListener listener = new RecordingListener();
        S4EventAdapter adapter = new S4EventAdapter(bridge, listener);

        adapter.prepareAsync("file:///video.mkv");
        adapter.setSurface(new Object());
        adapter.onFileLoaded();
        adapter.onSeek();
        adapter.setSurface(null);
        adapter.onPlaybackRestart();
        adapter.setSurface(new Object());

        assertEquals(1, listener.seekCompleteCount);
        assertEquals(1, countCallsStartingWith(bridge.calls, "load:"));
        assertEquals(2, countExactCalls(bridge.calls, "attach"));
        assertEquals(1, countExactCalls(bridge.calls, "detach"));
    }

    @Test
    public void releaseDetachesSurfaceBeforeDestroy() {
        RecordingBridge bridge = new RecordingBridge();
        S4EventAdapter adapter = new S4EventAdapter(bridge, new RecordingListener());
        adapter.setSurface(new Object());

        adapter.release();
        adapter.release();

        assertEquals("detach", bridge.calls.get(1));
        assertEquals("destroy", bridge.calls.get(2));
        assertEquals(3, bridge.calls.size());
    }

    private static int countCallsStartingWith(List<String> calls, String prefix) {
        int count = 0;
        for (String call : calls) {
            if (call.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static int countExactCalls(List<String> calls, String expected) {
        int count = 0;
        for (String call : calls) {
            if (expected.equals(call)) {
                count++;
            }
        }
        return count;
    }

    private static final class RecordingBridge implements S4EventAdapter.Bridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void attachSurface(Object surface) {
            calls.add("attach");
        }

        @Override
        public void detachSurface() {
            calls.add("detach");
        }

        @Override
        public void loadFile(String url) {
            calls.add("load:" + url);
        }

        @Override
        public void setPaused(boolean paused) {
            calls.add(paused ? "pause" : "start");
        }

        @Override
        public void destroy() {
            calls.add("destroy");
        }
    }

    private static final class RecordingListener implements S4EventAdapter.Listener {
        int preparedCount;
        int completionCount;
        int seekCompleteCount;
        final List<Integer> errors = new ArrayList<>();

        @Override
        public void onPrepared() {
            preparedCount++;
        }

        @Override
        public void onCompletion() {
            completionCount++;
        }

        @Override
        public void onError(int errorCode) {
            errors.add(errorCode);
        }

        @Override
        public void onSeekComplete() {
            seekCompleteCount++;
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int sarNum, int sarDen) {
        }
    }
}
