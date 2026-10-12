package io.github.jackotom.hongguotv;

public final class PlaybackStateTest {
    public static void main(String[] args) {
        check(PlaybackState.resumePosition(-1, 100000) == 0, "negative progress");
        check(PlaybackState.resumePosition(40000, 100000) == 40000, "resume middle");
        check(PlaybackState.resumePosition(99000, 100000) == 0, "finished episode restarts");
        check(PlaybackState.resumePosition(40000, 0) == 40000, "unknown duration");
        check(PlaybackState.seekPosition(5000, -10000, 60000) == 0, "seek start");
        check(PlaybackState.seekPosition(55000, 10000, 60000) == 59000, "seek end");
        check(PlaybackState.seekPosition(Integer.MAX_VALUE, 10000, Integer.MAX_VALUE) == Integer.MAX_VALUE - 1000, "seek overflow");
        check(PlaybackState.nextEpisode(0, 3, true) == 1, "advance");
        check(PlaybackState.nextEpisode(2, 3, true) == -1, "end of series");
        check(PlaybackState.nextEpisode(0, 3, false) == -1, "autoplay off");
        System.out.println("PlaybackState: 10 assertions passed");
    }
    static void check(boolean actual, String label) { if (!actual) throw new AssertionError(label); }
}
