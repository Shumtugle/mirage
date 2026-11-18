package com.shumtugle.mirage;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Presets live in one plain JSON file in the app's private storage:
 * { "version": 1, "presets": [ {name, zoom, ev, wb, default}, ... ] }
 * The same format can later be fetched from a remote module.
 */
final class PresetStore {
    private final File file;
    final List<Preset> list = new ArrayList<>();

    PresetStore(Context context) {
        file = new File(context.getFilesDir(), "presets.json");
        load();
    }

    void load() {
        list.clear();
        if (file.exists()) {
            try {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                JSONArray a = new JSONObject(text).getJSONArray("presets");
                for (int i = 0; i < a.length(); i++) list.add(Preset.fromJson(a.getJSONObject(i)));
            } catch (Exception e) {
                list.clear();
            }
        }
        if (list.isEmpty()) seed();
        ensureOneDefault();
    }

    /** First run: 2x on the main module is the starting point. */
    private void seed() {
        list.add(new Preset("2\u00d7", 2f, 0f, 0, true));
        list.add(new Preset("1\u00d7", 1f, 0f, 0, false));
        list.add(new Preset("3\u00d7", 3f, 0f, 0, false));
    }

    private void ensureOneDefault() {
        boolean found = false;
        for (Preset p : list) {
            if (p.isDefault) {
                if (found) p.isDefault = false;
                else found = true;
            }
        }
        if (!found && !list.isEmpty()) list.get(0).isDefault = true;
    }

    Preset getDefault() {
        for (Preset p : list) if (p.isDefault) return p;
        return list.get(0);
    }

    void setDefault(Preset chosen) {
        for (Preset p : list) p.isDefault = (p == chosen);
        save();
    }

    void remove(Preset p) {
        if (list.size() <= 1) return;
        list.remove(p);
        ensureOneDefault();
        save();
    }

    void save() {
        try {
            JSONArray a = new JSONArray();
            for (Preset p : list) a.put(p.toJson());
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("presets", a);
            // Write to a temp file first so a crash never leaves half a file behind.
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } catch (Exception ignored) {
            // Presets are a convenience; a failed save must not break shooting.
        }
    }
}
