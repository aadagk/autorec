package com.callrec.auto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView status, logView;
    private Button btnDelay;
    private Switch swAuto, swDebug;
    private EditText extra;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(28), p, p);
        sv.addView(root);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("Автозапись звонков");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        button("1. Разрешить доступ «Телефон»", new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 1);
            }
        });
        button("2. Разрешить «ограниченные настройки» (О приложении → ⋮)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this,
                        "Нажмите ⋮ (три точки) справа вверху → «Разрешить ограниченные настройки»",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        button("3. Включить службу спец. возможностей", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this,
                        "Установленные приложения → Автозапись → Включить", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        button("4. Не ограничивать работу в фоне", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });

        swAuto = new Switch(this);
        swAuto.setText("Автозапись всех звонков");
        swAuto.setTextSize(16);
        swAuto.setPadding(0, dp(16), 0, dp(4));
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setAuto(MainActivity.this, on); }
        });
        root.addView(swAuto);

        btnDelay = button("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setDelay(MainActivity.this, (Prefs.delay(MainActivity.this) + 1) % 11);
                refresh();
            }
        });

        swDebug = new Switch(this);
        swDebug.setText("Подробный лог и дамп экрана звонка");
        swDebug.setTextSize(16);
        swDebug.setPadding(0, dp(8), 0, dp(4));
        swDebug.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean on) { Prefs.setDebug(MainActivity.this, on); }
        });
        root.addView(swDebug);

        extra = new EditText(this);
        extra.setHint("Доп. пакеты звонилки через запятую (необязательно)");
        extra.setTextSize(14);
        extra.setSingleLine(true);
        root.addView(extra);

        TextView l = new TextView(this);
        l.setText("Журнал");
        l.setTextSize(18);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(0, dp(20), 0, 0);
        root.addView(l);

        button("Обновить журнал", new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        button("Поделиться журналом", new View.OnClickListener() {
            @Override public void onClick(View v) { share("Журнал Автозаписи", AppLog.readLog(MainActivity.this)); }
        });
        button("Поделиться дампом экрана звонка", new View.OnClickListener() {
            @Override public void onClick(View v) { share("Дамп экрана звонка", AppLog.readDump(MainActivity.this)); }
        });
        button("Очистить", new View.OnClickListener() {
            @Override public void onClick(View v) { AppLog.clear(MainActivity.this); refresh(); }
        });

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(8), 0, 0);
        root.addView(logView);
    }

    @Override protected void onResume() {
        super.onResume();
        extra.setText(Prefs.extraPkgs(this));
        refresh();
        if (RecService.inst != null) RecService.inst.registerPhone();
    }

    @Override protected void onPause() {
        Prefs.setExtraPkgs(this, extra.getText().toString().trim());
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (RecService.inst != null) RecService.inst.registerPhone();
        refresh();
    }

    private void refresh() {
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean acc = enabled != null && enabled.contains(getPackageName() + "/") && enabled.contains("RecService");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        status.setText((phone ? "✅" : "❌") + " Доступ «Телефон»\n"
                + (acc ? "✅" : "❌") + " Служба спец. возможностей\n"
                + (batt ? "✅" : "⚠️") + " Работа в фоне без ограничений");

        swAuto.setChecked(Prefs.auto(this));
        swDebug.setChecked(Prefs.debug(this));
        btnDelay.setText("Задержка перед нажатием: " + Prefs.delay(this) + " с (нажмите, чтобы изменить)");

        String log = AppLog.readLog(this);
        if (log.length() > 12000) log = "…\n" + log.substring(log.length() - 12000);
        logView.setText(log.isEmpty() ? "(пока пусто — совершите тестовый звонок)" : log);
    }

    private void share(String title, String text) {
        if (text.isEmpty()) {
            Toast.makeText(this, "Пока пусто", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.length() > 80000) text = text.substring(text.length() - 80000);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, title));
    }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(bt, lp);
        return bt;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
