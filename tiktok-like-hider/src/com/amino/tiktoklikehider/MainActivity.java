package com.amino.tiktoklikehider;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Settings UI. v1.3: + diagnostics mode switch + persistent diagnostics screen button. */
public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private CheckBox selectAll;
    private Switch diagSwitch;
    private final Map<String, Switch> switches = new LinkedHashMap<String, Switch>();
    private boolean uiUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS_NAME, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, p, p, p);
        root.setLayoutDirection(1);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("aMiNo TikTok Like Hider");
        title.setTextSize(21.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("اختر العناصر التي تريد إخفاءها من واجهة تيك توك — الإصدار 1.3");
        subtitle.setTextSize(13.0f);
        subtitle.setTextColor(0xFF444444);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        selectAll = new CheckBox(this);
        selectAll.setText("تحديد الكل — إخفاء جميع العناصر");
        selectAll.setTextSize(15.0f);
        selectAll.setTypeface(Typeface.DEFAULT_BOLD);
        selectAll.setPadding(0, dp(6), 0, dp(6));
        selectAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                setAll(isChecked);
                saveAndBroadcast();
                Toast.makeText(MainActivity.this,
                        isChecked ? "تم تحديد جميع العناصر" : "تم إلغاء تحديد الكل", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(selectAll, new LinearLayout.LayoutParams(-1, -2));

        for (final Config.Spec spec : Config.CATEGORIES) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            Switch sw = new Switch(this);
            sw.setText(spec.title);
            sw.setTextSize(16.0f);
            sw.setTextColor(Color.BLACK);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (uiUpdating) return;
                    saveAndBroadcast();
                    updateSelectAllState();
                }
            });
            row.addView(sw, new LinearLayout.LayoutParams(-1, -2));
            TextView desc = new TextView(this);
            desc.setText(spec.desc);
            desc.setTextSize(12.0f);
            desc.setTextColor(0xFF888888);
            row.addView(desc, new LinearLayout.LayoutParams(-1, -2));
            root.addView(row);
            switches.put(spec.key, sw);
        }

        diagSwitch = new Switch(this);
        diagSwitch.setText("وضع التشخيص — يسجل معرّفات تيك توك الفعلية في «سجل التشخيص» داخل هذا التطبيق (اتركه مفعّلاً حتى نضبط المطابقة)");
        diagSwitch.setTextSize(14.0f);
        diagSwitch.setPadding(0, dp(10), 0, dp(6));
        diagSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (uiUpdating) return;
                saveAndBroadcast();
            }
        });
        root.addView(diagSwitch, new LinearLayout.LayoutParams(-1, -2));

        Button apply = new Button(this);
        apply.setText("تطبيق وإرسال إلى تيك توك");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndBroadcast();
                Toast.makeText(MainActivity.this,
                        "تم إرسال الإعدادات — إن كان تيك توك مفتوحاً أعِد تشغيله لتطبيق كامل", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(apply, new LinearLayout.LayoutParams(-1, -2));

        Button openDiag = new Button(this);
        openDiag.setText("سجل التشخيص — محفوظ ولا يختفي (اضغط للمشاهدة والمشاركة)");
        openDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });
        root.addView(openDiag, new LinearLayout.LayoutParams(-1, -2));

        Button reset = new Button(this);
        reset.setText("استعادة الافتراضي (زر الإعجاب وعدده فقط)");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, Boolean> def = Config.defaultMap();
                uiUpdating = true;
                for (Config.Spec spec : Config.CATEGORIES) {
                    Switch sw = switches.get(spec.key);
                    if (sw != null) sw.setChecked(def.get(spec.key).booleanValue());
                }
                diagSwitch.setChecked(true);
                uiUpdating = false;
                saveAndBroadcast();
                updateSelectAllState();
            }
        });
        root.addView(reset, new LinearLayout.LayoutParams(-1, -2));

        TextView notes = new TextView(this);
        notes.setText("ملاحظات:\n"
                + "• عند بدء تيك توك سيسحب الإعدادات تلقائياً من هذا التطبيق مباشرة (حتى بعد إعادة تشغيل الهاتف).\n"
                + "• أعداد الإعجابات/التعليقات/المشاركات/الحفظ تُخفى تلقائياً مع زرها إن كان خيار العدد مفعلاً.\n"
                + "• تأكد أن ترقيع تيك توك أُجري بهذا الإصدار v1.3 — النسخ المرقّعة القديمة لا تحتوي السجل الدائم.\n"
                + "• بعد أي تغيير أعد تشغيل تيك توك لتطبيق كامل.\n"
                + "• إن لم يُخفَ عنصر: افتح «سجل التشخيص» من هذه الشاشة واضغط «مشاركة السجل» وأرسل لي النتيجة — السجل محفوظ هنا ولا يختفي.\n"
                + "• الحزم المدعومة: com.zhiliaoapp.musically و com.zhiliaoapp.musically.lite");
        notes.setTextSize(12.0f);
        notes.setTextColor(0xFF444444);
        notes.setPadding(0, dp(12), 0, dp(8));
        root.addView(notes, new LinearLayout.LayoutParams(-1, -2));

        loadIntoUi();
        setContentView(scroll);
    }

    private void setAll(boolean value) {
        uiUpdating = true;
        for (Switch sw : switches.values()) {
            sw.setChecked(value);
        }
        uiUpdating = false;
    }

    private void updateSelectAllState() {
        boolean all = true;
        for (Config.Spec spec : Config.CATEGORIES) {
            Switch sw = switches.get(spec.key);
            if (sw == null || !sw.isChecked()) {
                all = false;
                break;
            }
        }
        uiUpdating = true;
        selectAll.setChecked(all);
        uiUpdating = false;
    }

    private Map<String, Boolean> readPrefs() {
        Map<String, Boolean> map = Config.defaultMap();
        try {
            String json = prefs.getString(Config.PREF_KEY_JSON, null);
            if (json != null) {
                JSONObject o = new JSONObject(json);
                for (Config.Spec spec : Config.CATEGORIES) {
                    map.put(spec.key, Boolean.valueOf(o.optBoolean(spec.key, map.get(spec.key).booleanValue())));
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private void loadIntoUi() {
        Map<String, Boolean> map = readPrefs();
        uiUpdating = true;
        for (Config.Spec spec : Config.CATEGORIES) {
            Switch sw = switches.get(spec.key);
            if (sw != null) sw.setChecked(map.get(spec.key).booleanValue());
        }
        boolean diag = prefs.getBoolean("diag_mode", true);
        diagSwitch.setChecked(diag);
        uiUpdating = false;
        updateSelectAllState();
    }

    private void saveAndBroadcast() {
        try {
            JSONObject o = new JSONObject();
            for (Config.Spec spec : Config.CATEGORIES) {
                Switch sw = switches.get(spec.key);
                o.put(spec.key, sw != null && sw.isChecked());
            }
            boolean diag = diagSwitch == null || diagSwitch.isChecked();
            o.put(Config.KEY_DIAG, diag);
            prefs.edit().putBoolean("diag_mode", diag).apply();
            String json = o.toString();
            prefs.edit().putString(Config.PREF_KEY_JSON, json).apply();
            Intent intent = new Intent(Config.ACTION_CONFIG_UPDATE);
            intent.putExtra("config_json", json);
            // keep implicit so the dynamic receiver inside patched TikTok receives it
            sendBroadcast(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "خطأ أثناء الحفظ: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
