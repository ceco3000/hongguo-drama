package io.github.jackotom.hongguotv;

final class PlaybackState {
    static int resumePosition(int position, int duration) {
        if (position < 0 || (duration > 0 && position >= Math.max(0, duration - 3000))) return 0;
        return position;
    }
    static int seekPosition(int position, int delta, int duration) {
        long result = Math.max(0L, (long) position + delta);
        long limit = duration > 0 ? Math.max(0, duration - 1000) : Integer.MAX_VALUE;
        return (int) Math.min(result, limit);
    }
    static int nextEpisode(int index, int count, boolean enabled) {
        return enabled && index >= 0 && index + 1 < count ? index + 1 : -1;
    }
}
