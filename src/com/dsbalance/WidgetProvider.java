package com.dsbalance;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WidgetProvider extends AppWidgetProvider {

    private static final String ACTION_REFRESH = "com.dsbalance.WIDGET_REFRESH";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) bind(ctx, mgr, id, ctx.getSharedPreferences(BalanceApi.PREFS, 0));
        refreshAsync(ctx);
    }

    @Override
    public void onReceive(Context ctx, Intent it) {
        super.onReceive(ctx, it);
        String act = it.getAction();
        if (ACTION_REFRESH.equals(act)) {
            refreshAsync(ctx);
        } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(act)) {
            // 浅色/深色切换后重绘
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
            SharedPreferences p = ctx.getSharedPreferences(BalanceApi.PREFS, 0);
            for (int id : ids) bind(ctx, mgr, id, p);
        }
    }

    private static void bind(Context ctx, AppWidgetManager mgr, int id, SharedPreferences p) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget);

        Intent refresh = new Intent(ctx, WidgetProvider.class).setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getBroadcast(ctx, id, refresh,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(Intent.ACTION_MAIN);
        open.setClassName(ctx, "com.dsbalance.MainActivity");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        rv.setOnClickPendingIntent(R.id.wBalance, PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        rv.setTextViewText(R.id.wBalance, p.getString("w_balance", "¥ --"));
        rv.setTextViewText(R.id.wToday, p.getString("w_today", "今日 —"));
        rv.setTextViewText(R.id.wHit, p.getString("w_hit", "—%"));
        rv.setTextViewText(R.id.wKey, p.getString("w_key", "未设置 Key"));
        rv.setTextViewText(R.id.wTime, p.getString("w_time", ""));
        rv.setInt(R.id.wHit, "setTextColor",
                ctx.getColor(p.getInt("w_hit_res", R.color.ok)));
        rv.setInt(R.id.wKey, "setTextColor",
                ctx.getColor(p.getBoolean("w_key_missing", false)
                        ? R.color.err : R.color.widget_faint));

        mgr.updateAppWidget(id, rv);
    }

    private static void refreshAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            String balanceText = null, todayText = null, hitText = null;
            int hitRes = R.color.ok;
            String timeText;

            String key = BalanceApi.loadKey(app);
            if (key != null) {
                BalanceApi.Result r = BalanceApi.fetch(app, key);
                if (r.ok) {
                    balanceText = BalanceApi.symbol(r.currency) + r.total;
                } else {
                    balanceText = "¥ --";
                }
            }

            String token = BalanceApi.loadToken(app);
            if (token != null) {
                BalanceApi.Usage u = BalanceApi.fetchUsage(app, token);
                if (u.ok) {
                    long din = u.todayHit + u.todayMiss;
                    int dpct = din > 0 ? (int) (u.todayHit * 100 / din) : 0;
                    todayText = "今日 " + BalanceApi.fmtTokens(din)
                            + " 入 / " + BalanceApi.fmtTokens(u.todayOut) + " 出";
                    hitText = dpct + "%";
                    hitRes = dpct >= 80 ? R.color.ok : (dpct >= 50 ? R.color.warn : R.color.err);
                    hitText = (u.todayReq == 0) ? "—%" : dpct + "%";
                }
            }

            if (balanceText == null) balanceText = "¥ --";
            if (todayText == null) todayText = "今日 —";
            if (hitText == null) hitText = "—%";

            timeText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

            String masked = maskKey(key);

            SharedPreferences.Editor e = app.getSharedPreferences(BalanceApi.PREFS, 0).edit();
            e.putString("w_key", masked)
                    .putBoolean("w_key_missing", key == null)
                    .putString("w_balance", balanceText)
                    .putString("w_today", todayText)
                    .putString("w_hit", hitText)
                    .putInt("w_hit_res", hitRes)
                    .putString("w_time", timeText);
            e.apply();

            AppWidgetManager mgr = AppWidgetManager.getInstance(app);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(app, WidgetProvider.class));
            SharedPreferences p = app.getSharedPreferences(BalanceApi.PREFS, 0);
            for (int id : ids) bind(app, mgr, id, p);
        });
    }

    /** 供 App 内部调用：立刻拉一次并刷新小组件 */
    public static void refreshNow(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Context c = ctx;
        POOL.execute(() -> refreshAsync(c));
    }

    /** sk-abcdef...xyz123 -> sk-abc********123 */
    static String maskKey(String k) {
        if (k == null || k.isEmpty()) return "未设置 Key";
        String v = k;
        if (v.startsWith("sk-")) v = v.substring(3);
        if (v.length() <= 6) return "sk-***";
        return "sk-" + v.substring(0, 3) + "********" + v.substring(v.length() - 3);
    }

    static void push(Context ctx, String balance, String cur, String time) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(BalanceApi.PREFS, 0).edit();
        e.putString("w_balance", balance);
        String k = BalanceApi.loadKey(ctx);
        e.putString("w_key", maskKey(k));
        e.putBoolean("w_key_missing", k == null);
        if (time != null && !time.isEmpty()) e.putString("w_time", time);
        e.apply();

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        SharedPreferences p = ctx.getSharedPreferences(BalanceApi.PREFS, 0);
        for (int id : ids) bind(ctx, mgr, id, p);
    }
}
