package io.github.jackotom.hongguotv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class HistoryStore {
    private final SharedPreferences preferences;
    HistoryStore(Context context) { preferences = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE); }

    static final class Entry {
        final String seriesId, title, episodeId;
        final int episodeNumber, position, duration;
        Entry(String seriesId, String title, String episodeId, int episodeNumber, int position, int duration) {
            this.seriesId = seriesId; this.title = title; this.episodeId = episodeId;
            this.episodeNumber = episodeNumber; this.position = position; this.duration = duration;
        }
    }

    List<Entry> all() { return decode(preferences.getString("entries", "[]")); }
    Entry get(String id) {
        for (Entry entry : all()) if (entry.seriesId.equals(id)) return entry;
        return null;
    }
    void save(Entry entry) { preferences.edit().putString("entries", encode(entry, all())).apply(); }

    static List<Entry> decode(String raw) {
        List<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < Math.min(array.length(), 40); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                String id = value.optString("seriesId"), episode = value.optString("episodeId");
                if (!id.matches("[0-9]{6,30}") || !episode.matches("[0-9]{1,30}")) continue;
                result.add(new Entry(id, value.optString("title", "未命名短剧"), episode,
                    Math.max(1, value.optInt("number", 1)), Math.max(0, value.optInt("position")), Math.max(0, value.optInt("duration"))));
            }
        } catch (Exception ignored) { /* A damaged local record must not prevent launching. */ }
        return result;
    }
    static String encode(Entry latest, List<Entry> previous) {
        JSONArray array = new JSONArray();
        List<Entry> ordered = new ArrayList<>(); ordered.add(latest);
        for (Entry old : previous) if (!old.seriesId.equals(latest.seriesId) && ordered.size() < 40) ordered.add(old);
        for (Entry entry : ordered) {
            try {
                JSONObject value = new JSONObject();
                value.put("seriesId", entry.seriesId); value.put("title", entry.title); value.put("episodeId", entry.episodeId);
                value.put("number", entry.episodeNumber); value.put("position", Math.max(0, entry.position)); value.put("duration", Math.max(0, entry.duration));
                array.put(value);
            } catch (Exception ignored) { /* Values are local primitives; skip an unusable record. */ }
        }
        return array.toString();
    }
}
