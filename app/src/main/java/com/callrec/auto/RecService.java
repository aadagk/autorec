package com.callrec.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Служба специальных возможностей. Когда начинается звонок (OFFHOOK), она ищет в окне
 * звонилки кнопку «Записать». Если кнопка спрятана в меню «Еще» — сначала открывает его.
 * Вызов делается ровно один раз за звонок, чтобы случайно не выключить уже идущую запись.
 */
public class RecService extends AccessibilityService {

    static volatile RecService inst;

    // Пакеты экрана звонка: Samsung (incallui/dialer), Google, AOSP и т.п.
    private static final String[] PKG_PARTS = {"incallui", "dialer", "telecom", "telephonyui", "com.android.phone"};

    // Тексты (нормализованные: нижний регистр, «ё»→«е»)
    private static final String[] REC = {
            "записать", "записывать", "начать запись", "начать запись вызова", "записать вызов",
            "record", "start recording", "record call", "start call recording"};
    private static final String[] MORE = {
            "еще", "больше", "дополнительно", "другие параметры", "дополнительные параметры",
            "more", "more options", "other options"};
    private static final String[] STOP_CONTAINS = {
            "остановить запись", "прекратить запись", "завершить запись", "идет запись",
            "stop recording", "stop call recording", "recording in progress"};

    /** Сколько ждать кнопку после соединения. */
    private static final long MAX_WAIT_MS = 120_000;
    /** Если таймер разговора нигде не найден — через сколько считать, что соединение есть. */
    private static final long NO_TIMER_FALLBACK_MS = 60_000;
    private static final java.util.regex.Pattern TIMER =
            java.util.regex.Pattern.compile("^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})$");

    private final Handler h = new Handler(Looper.getMainLooper());
    private int prevState = -1;
    private boolean callActive, done, dumped, pkgsLogged, noWinLogged, phoneRegistered;
    private long offhookAt, connectedAt, lastAttemptAt, lastMoreAt, nextDelay = 900;
    private boolean waitLogged, inactiveLogged;
    private final Set<String> evPkgs = new HashSet<>();
    private BroadcastReceiver receiver;
    private Object telCb;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!callActive || done) return;
            if (!Prefs.auto(RecService.this)) return;
            if (connectedAt > 0 && SystemClock.uptimeMillis() - connectedAt > MAX_WAIT_MS + Prefs.delay(RecService.this) * 1000L) {
                done = true;
                AppLog.log(RecService.this, "Не нашёл кнопку «Записать» за " + (MAX_WAIT_MS / 1000)
                        + " с. Пришлите лог и дамп экрана (кнопки в приложении).");
                return;
            }
            try { attempt(); } catch (Throwable t) { AppLog.log(RecService.this, "Ошибка: " + t); }
            if (!done && callActive) {
                long d = nextDelay;
                nextDelay = 900;
                h.removeCallbacks(this);
                h.postDelayed(this, d);
            }
        }
    };

    private final Runnable verify = new Runnable() {
        @Override public void run() {
            if (!callActive) return;
            List<AccessibilityNodeInfo> roots = roots();
            boolean rec = false;
            for (AccessibilityNodeInfo r : roots) if (findStop(r, 0, new int[]{0}) != null) rec = true;
            AppLog.log(RecService.this, rec
                    ? "Проверка: индикатор записи найден — запись идёт."
                    : "Проверка: индикатор записи не найден (возможно, у вашей звонилки он называется иначе).");
            if (Prefs.debug(RecService.this)) dump(roots, "после нажатия «Записать»");
        }
    };

    // ---------------------------------------------------------------- жизненный цикл

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        AppLog.log(this, "Служба подключена. Android " + Build.VERSION.RELEASE + ", " + Build.MODEL);
        registerPhone();
        syncState();
    }

    @Override public boolean onUnbind(Intent intent) {
        unregisterPhone();
        inst = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        unregisterPhone();
        inst = null;
        super.onDestroy();
    }

    @Override public void onInterrupt() { }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!callActive || e == null) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (!done && Prefs.debug(this) && !pkg.toString().equals(getPackageName())
                && evPkgs.add(pkg.toString())) {
            AppLog.log(this, "Событие во время звонка из пакета: " + pkg);
        }
        if (done || !allowed(pkg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastAttemptAt > 500) {
            h.removeCallbacks(tick);
            h.postDelayed(tick, 250);
        }
    }

    // ---------------------------------------------------------------- состояние звонка

    /** Можно вызывать из активности: подписаться на состояние звонка после выдачи разрешения. */
    void registerPhone() {
        if (phoneRegistered) return;
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            AppLog.log(this, "Нет разрешения READ_PHONE_STATE — состояние звонка недоступно.");
            return;
        }
        try {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String s = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                    if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(s)) onCallState(2);
                    else if (TelephonyManager.EXTRA_STATE_RINGING.equals(s)) onCallState(1);
                    else if (TelephonyManager.EXTRA_STATE_IDLE.equals(s)) onCallState(0);
                }
            };
            IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(receiver, f);
        } catch (Throwable t) {
            AppLog.log(this, "Не удалось подписаться на PHONE_STATE: " + t);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                CallCb cb = new CallCb();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                telCb = cb;
            } catch (Throwable t) {
                AppLog.log(this, "TelephonyCallback недоступен: " + t);
            }
        }
        phoneRegistered = true;
        AppLog.log(this, "Слежение за состоянием звонка включено.");
    }

    private void unregisterPhone() {
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Throwable ignored) { }
        receiver = null;
        if (Build.VERSION.SDK_INT >= 31 && telCb != null) {
            try {
                ((TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE))
                        .unregisterTelephonyCallback((TelephonyCallback) telCb);
            } catch (Throwable ignored) { }
        }
        telCb = null;
        phoneRegistered = false;
    }

    /** Если службу включили посреди звонка. */
    private void syncState() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            int s = tm.getCallState();
            if (s == TelephonyManager.CALL_STATE_OFFHOOK) onCallState(2);
        } catch (Throwable ignored) { }
    }

    private class CallCb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) { onCallState(state); }
    }

    private void onCallState(int st) {
        if (st == prevState) return;
        prevState = st;
        AppLog.log(this, "Состояние звонка: " + (st == 2 ? "РАЗГОВОР/НАБОР" : st == 1 ? "ВХОДЯЩИЙ" : "СВОБОДНО"));
        if (st == 2) {
            if (callActive) return;
            callActive = true;
            done = false;
            dumped = false;
            pkgsLogged = false;
            noWinLogged = false;
            lastMoreAt = 0;
            nextDelay = 900;
            connectedAt = 0;
            waitLogged = false;
            inactiveLogged = false;
            offhookAt = SystemClock.uptimeMillis();
            evPkgs.clear();
            if (!Prefs.auto(this)) {
                AppLog.log(this, "Автозапись выключена — ничего не делаю.");
                return;
            }
            h.removeCallbacks(tick);
            h.postDelayed(tick, 1000);
        } else if (st == 0) {
            callActive = false;
            h.removeCallbacksAndMessages(null);
        }
    }

    // ---------------------------------------------------------------- основная логика

    private void attempt() {
        lastAttemptAt = SystemClock.uptimeMillis();
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            if (!noWinLogged) {
                noWinLogged = true;
                AppLog.log(this, "Окно звонилки не найдено. Окна: " + windowsSummary());
            }
            return;
        }
        noWinLogged = false;
        if (!pkgsLogged) {
            pkgsLogged = true;
            StringBuilder sb = new StringBuilder();
            for (AccessibilityNodeInfo r : roots) sb.append(r.getPackageName()).append(' ');
            AppLog.log(this, "Окно звонилки: " + sb.toString().trim());
        }
        if (Prefs.debug(this) && !dumped) {
            dumped = true;
            dump(roots, "первый осмотр экрана звонка");
        }

        // 0) ждём, пока собеседник ответит: пока идут гудки, кнопка записи неактивна
        long nowMs = SystemClock.uptimeMillis();
        if (connectedAt == 0) {
            if (timerRunning()) {
                connectedAt = nowMs;
                AppLog.log(this, "Собеседник ответил (идёт таймер разговора).");
            } else if (nowMs - offhookAt > NO_TIMER_FALLBACK_MS) {
                connectedAt = nowMs;
                AppLog.log(this, "Таймер разговора не найден за " + (NO_TIMER_FALLBACK_MS / 1000)
                        + " с — продолжаю без него.");
            } else {
                if (!waitLogged) {
                    waitLogged = true;
                    AppLog.log(this, "Жду ответа собеседника (гудки/набор)…");
                }
                return;
            }
        }
        if (nowMs - connectedAt < Prefs.delay(this) * 1000L) return;

        // 1) запись уже идёт?
        for (AccessibilityNodeInfo r : roots) {
            if (findStop(r, 0, new int[]{0}) != null) {
                done = true;
                AppLog.log(this, "Запись уже идёт — ничего не нажимаю.");
                return;
            }
        }

        // 2) кнопка «Записать» видна?
        AccessibilityNodeInfo rec = null;
        for (AccessibilityNodeInfo r : roots) {
            rec = find(r, REC, 0, new int[]{0});
            if (rec != null) break;
        }
        if (rec != null && !isActive(rec)) {
            if (!inactiveLogged) {
                inactiveLogged = true;
                AppLog.log(this, "Кнопка «" + label(rec) + "» неактивна — жду.");
            }
            return;
        }
        if (rec != null) {
            inactiveLogged = false;
            boolean ok = click(rec);
            AppLog.log(this, "Нажал «" + label(rec) + "»: " + (ok ? "успешно" : "НЕ удалось"));
            if (ok) {
                done = true;
                h.removeCallbacks(verify);
                h.postDelayed(verify, 2500);
            }
            return;
        }

        // 3) кнопки нет — открываем меню «Еще»
        long now = SystemClock.uptimeMillis();
        if (now - lastMoreAt > 2500) {
            AccessibilityNodeInfo more = null;
            for (AccessibilityNodeInfo r : roots) {
                more = find(r, MORE, 0, new int[]{0});
                if (more != null) break;
            }
            if (more != null) {
                lastMoreAt = now;
                boolean ok = click(more);
                AppLog.log(this, "Открываю меню «" + label(more) + "»: " + (ok ? "успешно" : "НЕ удалось"));
                if (Prefs.debug(this)) {
                    h.postDelayed(new Runnable() {
                        @Override public void run() { dump(roots(), "после нажатия «Еще»"); }
                    }, 800);
                }
                nextDelay = 600;
            }
        }
    }

    // ---------------------------------------------------------------- поиск узлов

    private boolean allowed(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        if (p.equals(getPackageName())) return false;
        for (String k : PKG_PARTS) if (p.contains(k)) return true;
        String extra = Prefs.extraPkgs(this);
        if (!extra.isEmpty()) {
            for (String e : extra.split("[,;\\s]+")) if (!e.isEmpty() && p.equals(e)) return true;
        }
        return false;
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && allowed(r.getPackageName())) out.add(r);
                }
            }
        } catch (Throwable ignored) { }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && allowed(r.getPackageName())) out.add(r);
        }
        return out;
    }

    private String windowsSummary() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    sb.append("[").append(r != null ? r.getPackageName() : "?")
                            .append(" type=").append(w.getType())
                            .append(" act=").append(w.isActive()).append("] ");
                }
            }
        } catch (Throwable t) { sb.append(t); }
        return sb.toString();
    }

    static String norm(CharSequence s) {
        if (s == null) return "";
        return s.toString().toLowerCase(Locale.ROOT).replace('ё', 'е').trim().replaceAll("\\s+", " ");
    }

    private static boolean matches(CharSequence cs, String[] words) {
        String t = norm(cs);
        if (t.isEmpty()) return false;
        for (String w : words) {
            if (t.equals(w) || t.startsWith(w + ",")) return true;
        }
        return false;
    }

    private static boolean matchesStop(CharSequence cs) {
        String t = norm(cs);
        if (t.isEmpty()) return false;
        if (t.equals("recording")) return true;
        for (String w : STOP_CONTAINS) if (t.contains(w)) return true;
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String[] words, int depth, int[] cnt) {
        if (n == null || depth > 60 || cnt[0]++ > 3000) return null;
        if (n.isVisibleToUser() && (matches(n.getText(), words) || matches(n.getContentDescription(), words))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), words, depth + 1, cnt);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findStop(AccessibilityNodeInfo n, int depth, int[] cnt) {
        if (n == null || depth > 60 || cnt[0]++ > 3000) return null;
        if (n.isVisibleToUser() && (matchesStop(n.getText()) || matchesStop(n.getContentDescription()))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findStop(n.getChild(i), depth + 1, cnt);
            if (r != null) return r;
        }
        return null;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "?" : t.toString();
    }

    // ---------------------------------------------------------------- соединение и активность

    /** Идёт ли таймер разговора (текст вида 00:01) в окне звонилки или в системной плашке звонка. */
    private boolean timerRunning() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                String p = String.valueOf(r.getPackageName());
                if (!(allowed(p) || p.contains("systemui"))) continue;
                if (timerIn(r, 0, new int[]{0})) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean timerIn(AccessibilityNodeInfo n, int depth, int[] cnt) {
        if (n == null || depth > 40 || cnt[0]++ > 1500) return false;
        if (isTimer(n.getText()) || isTimer(n.getContentDescription())) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (timerIn(n.getChild(i), depth + 1, cnt)) return true;
        }
        return false;
    }

    private static boolean isTimer(CharSequence cs) {
        if (cs == null) return false;
        java.util.regex.Matcher m = TIMER.matcher(cs.toString().trim());
        if (!m.matches()) return false;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long total = h * 3600 + Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3));
        return total >= 1;
    }

    /** Кнопка (или её кликабельный родитель) включена. */
    private static boolean isActive(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 7 && n != null; i++) {
            if (n.isClickable()) return n.isEnabled();
            n = n.getParent();
        }
        return node.isEnabled();
    }

    // ---------------------------------------------------------------- клики

    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 7 && n != null; i++) {
            if (n.isClickable() && n.isEnabled()) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            n = n.getParent();
        }
        // запасной вариант: настоящий «тап» по центру элемента
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() > 0 && r.height() > 0) return tap(r.centerX(), r.centerY());
        return false;
    }

    private boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- отладочный дамп

    private void dump(List<AccessibilityNodeInfo> roots, String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(AppLog.ts()).append("  ").append(why).append('\n');
        sb.append("Окна: ").append(windowsSummary()).append('\n');
        for (AccessibilityNodeInfo r : roots) {
            sb.append("--- root pkg=").append(r.getPackageName()).append('\n');
            walk(r, 0, sb, new int[]{0});
        }
        AppLog.dump(this, sb.toString());
    }

    private void walk(AccessibilityNodeInfo n, int d, StringBuilder sb, int[] cnt) {
        if (n == null || d > 40 || cnt[0]++ > 1500) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < d; i++) sb.append("  ");
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        sb.append(cls.substring(cls.lastIndexOf('.') + 1))
                .append(" [").append(n.isClickable() ? "c" : "-").append(n.isEnabled() ? "e" : "-")
                .append(n.isVisibleToUser() ? "v" : "-").append("]");
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.getText() != null) sb.append(" text=\"").append(n.getText()).append('"');
        if (n.getContentDescription() != null) sb.append(" desc=\"").append(n.getContentDescription()).append('"');
        sb.append(' ').append(r.toShortString()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), d + 1, sb, cnt);
    }
}
