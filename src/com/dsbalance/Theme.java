package com.dsbalance;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

public class Theme {

    public static final int AUTO = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String KEY = "theme_mode";

    public static int get(Context c) {
        return c.getSharedPreferences(BalanceApi.PREFS, 0).getInt(KEY, AUTO);
    }

    public static void set(Context c, int mode) {
        c.getSharedPreferences(BalanceApi.PREFS, 0).edit().putInt(KEY, mode).apply();
    }

    /** 在 setContentView 之前调用 */
    public static void apply(Activity a) {
        int mode = get(a);
        if (mode == AUTO) return; // 交给系统按 values / values-night 自动选
        Configuration cfg = new Configuration(a.getResources().getConfiguration());
        int night = (mode == DARK)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        if (cfg.uiMode != night) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (night & Configuration.UI_MODE_NIGHT_MASK);
            a.getResources().updateConfiguration(cfg, a.getResources().getDisplayMetrics());
        }
    }

    public static void bindChips(Activity a, View.OnClickListener onPick) {
        int mode = get(a);
        TextView[] chips = {
                a.findViewById(R.id.chipAuto),
                a.findViewById(R.id.chipLight),
                a.findViewById(R.id.chipDark)
        };
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            final int m = i;
            chips[i].setTag(m);
            chips[i].setSelected(m == mode);
            chips[i].setOnClickListener(v -> {
                set(v.getContext(), m);
                a.recreate();
            });
        }
    }
}
