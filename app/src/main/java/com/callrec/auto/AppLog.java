package com.callrec.auto;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Простой файловый лог: log.txt (события) и dump.txt (деревья экрана звонилки). */
final class AppLog {
    private AppLog() {}

    private static final int MAX_LOG = 100_000;
    private static final int MAX_DUMP = 300_000;

    static String ts() {
        return new SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT).format(new Date());
    }

    static synchronized void log(Context c, String msg) {
        append(new File(c.getFilesDir(), "log.txt"), ts() + "  " + msg + "\n", MAX_LOG);
    }

    static synchronized void dump(Context c, String text) {
        append(new File(c.getFilesDir(), "dump.txt"), text + "\n", MAX_DUMP);
    }

    static synchronized String readLog(Context c) { return read(new File(c.getFilesDir(), "log.txt")); }
    static synchronized String readDump(Context c) { return read(new File(c.getFilesDir(), "dump.txt")); }

    static synchronized void clear(Context c) {
        new File(c.getFilesDir(), "log.txt").delete();
        new File(c.getFilesDir(), "dump.txt").delete();
    }

    private static void append(File f, String s, int max) {
        try {
            if (f.exists() && f.length() > max) {
                String old = read(f);
                old = old.substring(old.length() / 2);
                try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(old.getBytes("UTF-8")); }
            }
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(s.getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }

    private static String read(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
