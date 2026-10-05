package uk.co.deanwild.materialshowcaseview.session;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Single-process, application-context store. Legacy numeric order is frozen on first import. */
public final class SharedPreferencesProgressStore implements ProgressStore {
    private static final Object LOCK = new Object();
    private static final String PREFIX = "tutorial_v2_";
    private final SharedPreferences prefs;
    public SharedPreferencesProgressStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("material_showcaseview_prefs", Context.MODE_PRIVATE);
    }
    private String key(String id) { return PREFIX + id; }
    @Override public Progress load(Tutorial tutorial) {
        synchronized (LOCK) {
            if (prefs.contains(key(tutorial.id))) return read(tutorial.id, tutorial.version).afterReset(tutorial.version);
            int legacy = 0;
            try { legacy = prefs.getInt("status_" + tutorial.id, 0); } catch (ClassCastException ignored) { }
            Set<String> completed = new HashSet<>();
            // Out-of-range records are untrusted: restart rather than silently finish.
            if (legacy > 0 && legacy <= tutorial.steps.size()) {
                for (int i = 0; i < legacy; i++) completed.add(tutorial.steps.get(i).id);
            }
            Progress imported = new Progress(1, tutorial.version, completed, Collections.emptySet(),
                    legacy == -1 ? Outcome.LEGACY_FINISHED : Outcome.ACTIVE,
                    legacy >= 0 && legacy < tutorial.steps.size() ? tutorial.steps.get(legacy).id : null);
            write(tutorial.id, imported); return imported;
        }
    }
    private Progress read(String id, int version) {
        try {
            JSONObject json = new JSONObject(prefs.getString(key(id), "{}"));
            return new Progress(json.getLong("revision"), json.getInt("version"),
                    strings(json.getJSONArray("completed")), strings(json.getJSONArray("skipped")),
                    Outcome.valueOf(json.getString("outcome")), json.isNull("next") ? null : json.optString("next", null));
        } catch (JSONException | IllegalArgumentException | ClassCastException error) {
            throw new IllegalStateException("Malformed tutorial progress: " + id + "; call resetProgress()", error);
        }
    }
    private Set<String> strings(JSONArray array) throws JSONException {
        Set<String> result = new HashSet<>(); for (int i = 0; i < array.length(); i++) result.add(array.getString(i)); return result;
    }
    private void write(String id, Progress p) {
        prefs.edit().putString(key(id), serialize(p)).apply();
    }
    private String serialize(Progress p) {
        try {
            JSONObject json = new JSONObject().put("revision", p.revision).put("version", p.version)
                    .put("completed", new JSONArray(p.completed)).put("skipped", new JSONArray(p.skipped)).put("outcome", p.outcome.name())
                    .put("next", p.nextStepId == null ? JSONObject.NULL : p.nextStepId);
            return json.toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    @Override public boolean save(String id, long expected, Progress update) {
        synchronized (LOCK) {
            long revision = prefs.contains(key(id)) ? read(id, update.version).revision : 0;
            if (revision != expected || update.revision != expected + 1) return false;
            write(id, update); return true;
        }
    }
    @Override public void reset(String id) {
        synchronized (LOCK) {
            prefs.edit().putString(key(id), serialize(resetRecord(id))).remove("status_" + id).apply();
        }
    }

    private Progress resetRecord(String id) {
        long revision = 0;
        if (prefs.contains(key(id))) {
            try { revision = read(id, 1).revision; }
            catch (IllegalStateException ignored) { revision = System.currentTimeMillis(); }
        }
        return new Progress(revision + 1, 0, Collections.emptySet(), Collections.emptySet(), Outcome.ACTIVE);
    }

    /** Reset every tutorial while retaining revisions that reject writers from before the reset. */
    public void resetAll() {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs.edit().clear();
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith(PREFIX))
                    editor.putString(key, serialize(resetRecord(key.substring(PREFIX.length()))));
            }
            editor.apply();
        }
    }
}
