package com.shumtugle.mirage;

import org.json.JSONException;
import org.json.JSONObject;

/** One named set of capture settings. */
final class Preset {
    String name;
    /** Zoom ratio on the main rear module, 1.0 = no crop. */
    float zoom;
    /** Exposure compensation in EV. */
    float ev;
    /** Index into CameraEngine.WB_MODES. */
    int wb;
    /** The preset the camera starts with. Exactly one per list. */
    boolean isDefault;

    Preset(String name, float zoom, float ev, int wb, boolean isDefault) {
        this.name = name;
        this.zoom = zoom;
        this.ev = ev;
        this.wb = wb;
        this.isDefault = isDefault;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("zoom", (double) zoom);
        o.put("ev", (double) ev);
        o.put("wb", wb);
        o.put("default", isDefault);
        return o;
    }

    static Preset fromJson(JSONObject o) {
        int wb = o.optInt("wb", 0);
        if (wb < 0 || wb >= CameraEngine.WB_MODES.length) wb = 0;
        return new Preset(
                o.optString("name", "?"),
                (float) o.optDouble("zoom", 1.0),
                (float) o.optDouble("ev", 0.0),
                wb,
                o.optBoolean("default", false));
    }
}
