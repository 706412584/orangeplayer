package com.orange.player.mpv.spike;

/**
 * Pure-Java spike for validating libmpv event ordering without loading native code.
 */
public final class S4EventAdapter {

    public enum EndReason {
        EOF,
        STOP,
        ERROR
    }

    public interface Bridge {
        void attachSurface(Object surface);

        void detachSurface();

        void loadFile(String url);

        void setPaused(boolean paused);

        void destroy();
    }

    public interface Listener {
        void onPrepared();

        void onCompletion();

        void onError(int errorCode);

        void onSeekComplete();

        void onVideoSizeChanged(int width, int height, int sarNum, int sarDen);
    }

    private final Bridge bridge;
    private final Listener listener;

    private String pendingUrl;
    private boolean surfaceAttached;
    private boolean loadIssued;
    private boolean prepared;
    private boolean paused = true;
    private boolean seekPending;
    private boolean released;
    private long currentPositionMs;
    private long durationMs;

    public S4EventAdapter(Bridge bridge, Listener listener) {
        this.bridge = bridge;
        this.listener = listener;
    }

    public void prepareAsync(String url) {
        checkNotReleased();
        pendingUrl = url;
        maybeLoadFile();
    }

    public void setSurface(Object surface) {
        checkNotReleased();
        if (surfaceAttached) {
            bridge.detachSurface();
            surfaceAttached = false;
        }
        if (surface != null) {
            bridge.attachSurface(surface);
            surfaceAttached = true;
            maybeLoadFile();
        }
    }

    public void start() {
        checkNotReleased();
        paused = false;
        bridge.setPaused(false);
        maybeLoadFile();
    }

    public void pause() {
        checkNotReleased();
        paused = true;
        bridge.setPaused(true);
    }

    public boolean isPlaying() {
        return prepared && !paused;
    }

    public void onFileLoaded() {
        checkNotReleased();
        prepared = true;
        listener.onPrepared();
    }

    public void onTimePosition(double seconds) {
        checkNotReleased();
        currentPositionMs = secondsToMillis(seconds);
    }

    public void onDuration(double seconds) {
        checkNotReleased();
        durationMs = secondsToMillis(seconds);
    }

    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void onSeek() {
        checkNotReleased();
        seekPending = true;
    }

    public void onPlaybackRestart() {
        checkNotReleased();
        if (seekPending) {
            seekPending = false;
            listener.onSeekComplete();
        }
    }

    public void onEndFile(EndReason reason, int errorCode) {
        checkNotReleased();
        if (reason == EndReason.EOF) {
            listener.onCompletion();
        } else if (reason == EndReason.ERROR) {
            listener.onError(errorCode);
        }
    }

    public void onVideoReconfig(int width, int height, int sarNum, int sarDen) {
        checkNotReleased();
        listener.onVideoSizeChanged(width, height, sarNum, sarDen);
    }

    public void release() {
        if (released) {
            return;
        }
        if (surfaceAttached) {
            bridge.detachSurface();
            surfaceAttached = false;
        }
        bridge.destroy();
        released = true;
    }

    private void maybeLoadFile() {
        if (!loadIssued && surfaceAttached && pendingUrl != null) {
            bridge.loadFile(pendingUrl);
            loadIssued = true;
        }
    }

    private void checkNotReleased() {
        if (released) {
            throw new IllegalStateException("adapter released");
        }
    }

    private static long secondsToMillis(double seconds) {
        return Math.round(seconds * 1000d);
    }
}
