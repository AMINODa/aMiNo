package com.amino.tiktoklikehider;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

/**
 * v1.3: persistent diagnostics viewer — the log lives in the app's private storage,
 * so it never disappears like the LSPatch log page. Buttons: refresh / copy all /
 * share / clear.
 */
public class DiagActivity extends Activity {

    private TextView body;
    private TextView stat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutDirection(1);

        TextView title = new TextView(this);
        title.setText("سجل التشخيص — محفوظ ولا يختفي");
        title.setTextSize(19.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("هذا السجل يُكتب داخل هذا التطبيق مباشرة من تيك توك المرقّع، ولا يختفي.\nالخطوات: افتح تيك توك وسِر فيه 20-30 ثانية → ارجع هنا → اضغط «تحديث» → ثم «مشاركة السجل» وأرسل النتيجة لي.");
        hint.setTextSize(13.0f);
        hint.setTextColor(0xFF555555);
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = mkButton("تحديث");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLog();
            }
        });
        Button copy = mkButton("نسخ الكل");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        Button share = mkButton("مشاركة السجل");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });
        Button clear = mkButton("مسح");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });
        row.addView(refresh, rowParams());
        row.addView(copy, rowParams());
        row.addView(share, rowParams());
        row.addView(clear, rowParams());
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        stat = new TextView(this);
        stat.setTextSize(12.0f);
        stat.setTextColor(0xFF00695C);
        stat.setPadding(0, dp(6), 0, dp(4));
        root.addView(stat, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        body = new TextView(this);
        body.setTextSize(11.0f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(0xFF212121);
        body.setMovementMethod(new ScrollingMovementMethod());
        sc.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, -1));

        setContentView(root);
        loadLog();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1.0f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private Button mkButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13.0f);
        b.setAllCaps(false);
        return b;
    }

    private File logFile() {
        return new File(getFilesDir(), DiagReceiver.DIAG_FILE);
    }

    private void loadLog() {
        String text = "";
        try {
            File f = logFile();
            if (f.exists()) {
                String all = DiagReceiver.readAll(f);
                if (all.length() > 40000) {
                    text = "… (السجل أطول من هذا العرض — «نسخ الكل» و«مشاركة السجل» يرسلان كامل السجل)\n\n"
                            + all.substring(all.length() - 40000);
                } else {
                    text = all;
                }
                int lines = 0;
                for (int i = 0; i < all.length(); i++) {
                    if (all.charAt(i) == '\n') lines++;
                }
                stat.setText("آخر تحديث: " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
                        + " — عدد الأسطر: " + lines);
            } else {
                text = "(السجل فارغ حالياً — افتح تيك توك المرقّع بوحدة v1.3 وسِر فيه قليلاً ثم اضغط تحديث)";
                stat.setText("لا يوجد سجل بعد");
            }
        } catch (Throwable t) {
            text = "(خطأ في قراءة السجل: " + t.getClass().getSimpleName() + ")";
        }
        body.setText(text.isEmpty() ? "(فارغ)" : text);
    }

    private void copyAll() {
        try {
            File f = logFile();
            if (!f.exists()) {
                Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show();
                return;
            }
            String all = DiagReceiver.readAll(f);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("aMiNoLikeHider diag", all));
                Toast.makeText(this, "تم نسخ السجل كاملاً (" + all.length() + " حرف)", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "فشل النسخ: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLog() {
        try {
            File f = logFile();
            if (!f.exists()) {
                Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show();
                return;
            }
            String all = DiagReceiver.readAll(f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "aMiNoLikeHider diagnostics");
            send.putExtra(Intent.EXTRA_TEXT, all);
            startActivity(Intent.createChooser(send, "أرسل السجل عبر…"));
        } catch (Throwable t) {
            Toast.makeText(this, "فشل المشاركة: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLog() {
        try {
            File f = logFile();
            if (f.exists()) {
                f.delete();
            }
            loadLog();
            Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "فشل المسح: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
