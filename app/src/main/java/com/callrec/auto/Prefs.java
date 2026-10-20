package com.callrec.auto;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static boolean auto(Context c) { return sp(c).getBoolean("auto", true); }
    static void setAuto(Context c, boolean v) { sp(c).edit().putBoolean("auto", v).apply(); }

    /** Задержка перед первой попыткой нажать «Записать», секунд. */
    static int delay(Context c) { return sp(c).getInt("delay", 2); }
    static void setDelay(Context c, int v) { sp(c).edit().putInt("delay", v).apply(); }

    static boolean debug(Context c) { return sp(c).getBoolean("debug", true); }
    static void setDebug(Context c, boolean v) { sp(c).edit().putBoolean("debug", v).apply(); }

    /** Дополнительные пакеты звонилки через запятую. */
    static String extraPkgs(Context c) { return sp(c).getString("extra", ""); }
    static void setExtraPkgs(Context c, String v) { sp(c).edit().putString("extra", v).apply(); }
}
