package com.phonedoctor.honor;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // مستويات الخطورة
    private static final int OK = 0, INFO = 1, WARN = 2, BAD = 3;

    private static class Finding {
        final int sev;
        final String title;
        final String detail;

        Finding(int sev, String title, String detail) {
            this.sev = sev;
            this.title = title;
            this.detail = detail;
        }
    }

    private LinearLayout container;
    private TextView verdictView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        container = findViewById(R.id.container);
        verdictView = findViewById(R.id.verdict);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());

        findViewById(R.id.btnUsage).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception e2) {
                    Toast.makeText(this, "تعذر فتح الإعدادات", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    // ------------------------------------------------------------------
    // الفحص الرئيسي
    // ------------------------------------------------------------------
    private void refresh() {
        List<Finding> list = new ArrayList<>();
        safe(() -> checkBattery(list));
        safe(() -> checkThermal(list));
        safe(() -> checkMemory(list));
        safe(() -> checkStorage(list));
        safe(() -> checkSettings(list));
        safe(() -> checkUptime(list));
        safe(() -> checkCpu(list));
        safe(() -> checkUsage(list));
        list.add(new Finding(INFO, "💡 خطوات عامة للحل",
                "1) أعد تشغيل الهاتف.\n"
                        + "2) حدّث النظام (إعدادات ← النظام والتحديثات).\n"
                        + "3) من إعدادات البطارية ← تشغيل التطبيقات: عطّل التشغيل التلقائي للتطبيقات غير الضرورية.\n"
                        + "4) احذف التطبيقات التي لا تستخدمها، خاصة التي تعمل في الخلفية.\n"
                        + "5) إذا بقيت المشكلة: انسخ بياناتك احتياطياً ثم أعد ضبط المصنع."));

        // ترتيب: الأخطر أولاً
        Collections.sort(list, (a, b) -> b.sev - a.sev);

        container.removeAllViews();
        int bad = 0, warn = 0;
        for (Finding f : list) {
            if (f.sev == BAD) bad++;
            if (f.sev == WARN) warn++;
            container.addView(buildCard(f));
        }

        if (bad > 0) {
            verdictView.setText("🔴 وُجدت " + bad + " مشكلة خطيرة و" + warn
                    + " تحذيرات. ابدأ من البطاقات الحمراء أدناه.");
        } else if (warn > 0) {
            verdictView.setText("🟠 وُجدت " + warn + " أسباب محتملة للبطء/السخونة. راجع البطاقات البرتقالية.");
        } else {
            verdictView.setText("🟢 لا شيء غير طبيعي الآن. إذا كانت السخونة تحدث أثناء استخدام معيّن "
                    + "(ألعاب/كاميرا/شحن)، افتح التطبيق وقت حدوثها ثم راجع النتائج.");
        }
    }

    private interface Check {
        void run() throws Exception;
    }

    private void safe(Check c) {
        try {
            c.run();
        } catch (Exception ignored) {
            // لا نوقف الفحص بسبب فحص واحد فاشل
        }
    }

    // ------------------------------------------------------------------
    // البطارية والحرارة
    // ------------------------------------------------------------------
    private void checkBattery(List<Finding> list) {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;

        float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale > 0 ? level * 100 / scale : -1;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        int volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        String info = String.format(Locale.US, "حرارة البطارية: %.1f°م\nالشحن: %d%%%s\nالجهد: %d mV",
                temp, pct, charging ? " (يشحن)" : "", volt);

        if (temp >= 45) {
            list.add(new Finding(BAD, "🔥 البطارية ساخنة جداً",
                    info + "\n\nفوق 45°م خطر على البطارية والأداء. افصل الشاحن، أغلق التطبيقات الثقيلة، "
                            + "وأخرج الغلاف الواقي ودع الهاتف يبرد."));
        } else if (temp >= 40) {
            list.add(new Finding(WARN, "🌡️ البطارية دافئة أكثر من الطبيعي",
                    info + "\n\nالطبيعي أقل من 38°م. "
                            + (charging ? "الشحن أثناء الاستخدام يرفع الحرارة كثيراً؛ جرّب الشحن والهاتف مطفأ الشاشة. " : "")
                            + "راجع التطبيقات المستهلكة بالأسفل."));
        } else {
            list.add(new Finding(OK, "🔋 حرارة البطارية طبيعية", info));
        }

        if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT
                || health == BatteryManager.BATTERY_HEALTH_DEAD
                || health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) {
            list.add(new Finding(BAD, "⚠️ النظام يبلغ عن مشكلة في صحة البطارية",
                    "الحالة المبلغ عنها: " + healthName(health)
                            + "\nقد يكون سبب السخونة والبطء بطارية متضررة. يُفضَّل فحصها في مركز صيانة Honor."));
        } else if (health == BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE) {
            list.add(new Finding(WARN, "صحة البطارية: عطل غير محدد", "قد تحتاج لفحص في مركز الصيانة."));
        }

        if (pct >= 0 && pct < 15 && !charging) {
            list.add(new Finding(WARN, "🪫 البطارية منخفضة",
                    "عند انخفاض الشحن يخفّض النظام أداء المعالج لتوفير الطاقة، وهذا يسبب بطئاً."));
        }
    }

    private String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "ارتفاع حرارة";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "تالفة";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "جهد زائد";
            default:
                return "غير معروفة";
        }
    }

    private void checkThermal(List<Finding> list) {
        if (Build.VERSION.SDK_INT < 29) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        int t = pm.getCurrentThermalStatus();
        if (t >= PowerManager.THERMAL_STATUS_SEVERE) {
            list.add(new Finding(BAD, "🔥 النظام يخنق أداء الهاتف بسبب الحرارة",
                    "حالة الحرارة (مستوى " + t + " من 6). النظام يخفّض سرعة المعالج عمداً — وهذا سبب مباشر للبطء. "
                            + "دع الهاتف يبرد وأغلق التطبيقات الثقيلة."));
        } else if (t >= PowerManager.THERMAL_STATUS_LIGHT) {
            list.add(new Finding(WARN, "🌡️ بدأ النظام بالتحكم في الحرارة",
                    "حالة الحرارة (مستوى " + t + " من 6). قد يبدأ تخفيض الأداء قريباً."));
        } else {
            list.add(new Finding(OK, "✅ حالة الحرارة في النظام: طبيعية", "لا يوجد تخفيض أداء بسبب الحرارة حالياً."));
        }
    }

    // ------------------------------------------------------------------
    // الذاكرة والتخزين
    // ------------------------------------------------------------------
    private void checkMemory(List<Finding> list) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) return;

        int freePct = (int) (mi.availMem * 100 / mi.totalMem);
        String info = String.format(Locale.US, "المتاحة: %s من %s (%d%%)",
                gb(mi.availMem), gb(mi.totalMem), freePct);

        if (mi.lowMemory || freePct < 12) {
            list.add(new Finding(BAD, "🧠 الذاكرة العشوائية (RAM) شبه ممتلئة",
                    info + "\n\nعند امتلاء الذاكرة يقتل النظام التطبيقات ويعيد تشغيلها باستمرار، فيسخن الهاتف ويبطؤ. "
                            + "أغلق التطبيقات الأخيرة وقلّل التطبيقات التي تعمل في الخلفية."));
        } else if (freePct < 22) {
            list.add(new Finding(WARN, "🧠 الذاكرة العشوائية منخفضة نسبياً", info));
        } else {
            list.add(new Finding(OK, "🧠 الذاكرة العشوائية جيدة", info));
        }
    }

    private void checkStorage(List<Finding> list) {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes();
        long free = fs.getAvailableBytes();
        if (total <= 0) return;

        int freePct = (int) (free * 100 / total);
        String info = String.format(Locale.US, "المتاح: %s من %s (%d%%)", gb(free), gb(total), freePct);

        if (freePct < 10) {
            list.add(new Finding(BAD, "💾 مساحة التخزين تكاد تنفد",
                    info + "\n\nأقل من 10% مساحة حرة يبطّئ الهاتف بشدة (الذاكرة الافتراضية والتخزين المؤقت). "
                            + "احذف ملفات وتطبيقات غير مهمة أو انقلها للسحابة."));
        } else if (freePct < 20) {
            list.add(new Finding(WARN, "💾 مساحة التخزين قليلة", info + "\n\nيُفضَّل إبقاء 20% على الأقل فارغة."));
        } else {
            list.add(new Finding(OK, "💾 مساحة التخزين جيدة", info));
        }
    }

    // ------------------------------------------------------------------
    // إعدادات النظام المؤثرة
    // ------------------------------------------------------------------
    private void checkSettings(List<Finding> list) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isPowerSaveMode()) {
            list.add(new Finding(WARN, "🔌 وضع توفير الطاقة مفعّل",
                    "هذا الوضع يقلّل أداء المعالج عمداً ويجعل الهاتف يبدو بطيئاً. أوقفه لو كان البطء هو المشكلة."));
        }

        float a = Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        float t = Settings.Global.getFloat(getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
        float w = Settings.Global.getFloat(getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
        if (a > 1f || t > 1f || w > 1f) {
            list.add(new Finding(WARN, "🐢 سرعة الحركات (Animations) مبطّأة",
                    String.format(Locale.US, "المقاييس: %.1f / %.1f / %.1f\nمن خيارات المطور اجعلها 1x أو أقل لتبدو الواجهة أسرع.", a, t, w)));
        }

        int alwaysFinish = Settings.Global.getInt(getContentResolver(), "always_finish_activities", 0);
        if (alwaysFinish == 1) {
            list.add(new Finding(WARN, "♻️ خيار «عدم الاحتفاظ بالأنشطة» مفعّل",
                    "هذا الخيار في خيارات المطور يجعل التطبيقات تُغلق وتُعاد عند كل خروج، فيزيد البطء والسخونة. عطّله."));
        }
    }

    private void checkUptime(List<Finding> list) {
        long days = SystemClock.elapsedRealtime() / (24L * 3600 * 1000);
        if (days >= 7) {
            list.add(new Finding(WARN, "⏱️ الهاتف لم يُعاد تشغيله منذ " + days + " يوماً",
                    "إعادة التشغيل تنظّف الذاكرة والعمليات العالقة وغالباً تحسّن الأداء فوراً."));
        } else {
            list.add(new Finding(OK, "⏱️ مدة التشغيل منذ آخر إعادة تشغيل: " + days + " يوم", "طبيعي."));
        }
    }

    private void checkCpu(List<Finding> list) {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxKhz = 0;
        for (int i = 0; i < cores; i++) {
            String s = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (s == null) continue;
            try {
                maxKhz = Math.max(maxKhz, Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String detail = "عدد الأنوية: " + cores;
        if (maxKhz > 0) detail += "\nأعلى تردد حالي: " + (maxKhz / 1000) + " MHz";
        else detail += "\n(النظام لا يسمح بقراءة تردد المعالج على هذا الجهاز)";
        list.add(new Finding(INFO, "⚙️ المعالج", detail));
    }

    private String readLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // أكثر التطبيقات استخداماً (يتطلب إذن بيانات الاستخدام)
    // ------------------------------------------------------------------
    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void checkUsage(List<Finding> list) {
        if (!hasUsageAccess()) {
            list.add(new Finding(INFO, "🔍 لمعرفة التطبيق المسبب",
                    "اضغط زر «منح إذن بيانات الاستخدام» بالأسفل، ثم فعّل الإذن لتطبيق «طبيب الهاتف»، "
                            + "وارجع هنا لأعرض لك أكثر التطبيقات استخداماً خلال آخر 24 ساعة."));
            return;
        }

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        long start = end - 24L * 3600 * 1000;
        Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(start, end);
        if (map == null || map.isEmpty()) return;

        List<UsageStats> stats = new ArrayList<>();
        for (UsageStats u : map.values()) {
            if (u.getTotalTimeInForeground() > 60_000 && !u.getPackageName().equals(getPackageName())) {
                stats.add(u);
            }
        }
        Collections.sort(stats, (x, y) -> Long.compare(y.getTotalTimeInForeground(), x.getTotalTimeInForeground()));
        if (stats.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        long topMs = stats.get(0).getTotalTimeInForeground();
        for (int i = 0; i < Math.min(6, stats.size()); i++) {
            UsageStats u = stats.get(i);
            sb.append("• ").append(appLabel(u.getPackageName())).append(" — ")
                    .append(fmtDuration(u.getTotalTimeInForeground())).append("\n");
        }

        boolean heavy = topMs >= 3L * 3600 * 1000;
        if (heavy) {
            sb.append("\nأكثر تطبيق استُخدم أكثر من 3 ساعات. الألعاب والكاميرا والفيديو والملاحة (GPS) "
                    + "من أكثر أسباب السخونة. جرّب إغلاقه أو تحديثه أو حذف ذاكرته المؤقتة.");
        } else {
            sb.append("\nقارن هذه القائمة بإعدادات البطارية؛ أي تطبيق مجهول أو غير مستخدم يعمل بكثرة فهو مشتبه به.");
        }
        list.add(new Finding(heavy ? WARN : INFO, "📱 أكثر التطبيقات استخداماً (24 ساعة)", sb.toString().trim()));
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private String fmtDuration(long ms) {
        long m = ms / 60000;
        long h = m / 60;
        m = m % 60;
        return h > 0 ? h + " س " + m + " د" : m + " د";
    }

    private String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    // ------------------------------------------------------------------
    // واجهة البطاقات
    // ------------------------------------------------------------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout buildCard(Finding f) {
        int bg, bar;
        switch (f.sev) {
            case BAD:
                bg = Color.parseColor("#FDECEA");
                bar = Color.parseColor("#D93025");
                break;
            case WARN:
                bg = Color.parseColor("#FFF4E5");
                bar = Color.parseColor("#F29900");
                break;
            case INFO:
                bg = Color.parseColor("#E8F0FE");
                bar = Color.parseColor("#1A73E8");
                break;
            default:
                bg = Color.parseColor("#E6F4EA");
                bar = Color.parseColor("#188038");
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), bar);
        card.setBackground(shape);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(f.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1B2733"));
        card.addView(title);

        TextView detail = new TextView(this);
        detail.setText(f.detail);
        detail.setTextSize(14);
        detail.setTextColor(Color.parseColor("#3C4650"));
        detail.setPadding(0, dp(6), 0, 0);
        detail.setLineSpacing(0, 1.2f);
        card.addView(detail);

        return card;
    }
}
