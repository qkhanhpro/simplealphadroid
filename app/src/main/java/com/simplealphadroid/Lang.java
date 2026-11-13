package com.simplealphadroid;

import android.content.Context;

/**
 * App language on phone 1: Vietnamese or English. Saved in the "ui" prefs as "lang" = "vi" | "en"; until the user
 * picks one it follows the phone's language (Vietnamese phone: Vietnamese, anything else: English).
 */
final class Lang {
    static volatile boolean vi = true;

    private Lang() {}

    static void init(Context c) {
        String sys = java.util.Locale.getDefault().getLanguage();
        String saved = c.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lang", null);
        vi = saved != null ? !"en".equals(saved) : "vi".equals(sys);
    }

    static void set(Context c, boolean toVi) {
        vi = toVi;
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("lang", toVi ? "vi" : "en").apply();
    }

    /** The text in the current language. */
    static String t(String en, String viText) { return vi ? viText : en; }
}
