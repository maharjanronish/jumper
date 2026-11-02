package io.github.maharjanronish.jumper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fully offline persistence: best score, a top-10 local leaderboard, the
 * sound toggle and the first-run flag. No network, no accounts, no tracking.
 * (Prefs file "jumper" so the game coexists with the original "jumpjump".)
 */
public final class Store {

    public static final class Record {
        public final int score;
        public final long timestamp;
        Record(int score, long timestamp) { this.score = score; this.timestamp = timestamp; }
    }

    private static final String PREFS = "jumper";
    private static final String KEY_BEST = "best";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_SOUND = "sound_on";
    private static final String KEY_FIRST_RUN = "first_run_done";

    private final SharedPreferences prefs;

    public Store(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int best() { return prefs.getInt(KEY_BEST, 0); }

    public boolean soundOn() { return prefs.getBoolean(KEY_SOUND, true); }

    public void setSoundOn(boolean on) { prefs.edit().putBoolean(KEY_SOUND, on).apply(); }

    public boolean firstRunDone() { return prefs.getBoolean(KEY_FIRST_RUN, false); }

    public void markFirstRunDone() { prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply(); }

    /** Adds a finished run. Returns the 1-based rank, or -1 if outside the top 10. */
    public int addScore(int score) {
        List<Record> list = records();
        list.add(new Record(score, System.currentTimeMillis()));
        list.sort((a, b) -> b.score != a.score ? Integer.compare(b.score, a.score)
                : Long.compare(a.timestamp, b.timestamp));
        List<Record> top = list.subList(0, Math.min(10, list.size()));

        JSONArray arr = new JSONArray();
        try {
            for (Record r : top) {
                JSONObject o = new JSONObject();
                o.put("s", r.score);
                o.put("t", r.timestamp);
                arr.put(o);
            }
            prefs.edit().putString(KEY_RECORDS, arr.toString()).apply();
        } catch (Exception ignored) { }

        if (score > best()) prefs.edit().putInt(KEY_BEST, score).apply();

        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).score == score) {
                // rank among equal scores is newest-later; first match is enough
                return i + 1;
            }
        }
        return -1;
    }

    public List<Record> records() {
        List<Record> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_RECORDS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Record(o.getInt("s"), o.getLong("t")));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
