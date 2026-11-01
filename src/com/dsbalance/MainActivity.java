package com.dsbalance;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private TextView tvBalance, tvStatus, tvDetail, tvTime;
    private TextView tvUsageStatus, tvInTokens, tvOutTokens, tvReqCount;
    private TextView tvHitRate, tvCacheDetail, tvHint;
    private ProgressBar pbCache;

    private SecureField keyField, tokenField;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.apply(this);
        setContentView(R.layout.activity_main);
        Theme.bindChips(this, null);

        tvBalance = findViewById(R.id.tvBalance);
        tvStatus = findViewById(R.id.tvStatus);
        tvDetail = findViewById(R.id.tvDetail);
        tvTime = findViewById(R.id.tvTime);
        tvUsageStatus = findViewById(R.id.tvUsageStatus);
        tvInTokens = findViewById(R.id.tvInTokens);
        tvOutTokens = findViewById(R.id.tvOutTokens);
        tvReqCount = findViewById(R.id.tvReqCount);
        tvHitRate = findViewById(R.id.tvHitRate);
        tvCacheDetail = findViewById(R.id.tvCacheDetail);
        tvHint = findViewById(R.id.tvHint);
        pbCache = findViewById(R.id.pbCache);

        keyField = new SecureField(this, (EditText) findViewById(R.id.etKey), "API Key");
        tokenField = new SecureField(this, (EditText) findViewById(R.id.etToken), "userToken");

        keyField.setPlain(BalanceApi.loadKey(this));
        tokenField.setPlain(BalanceApi.loadToken(this));

        // 输入框本体：无值 -> 录入；有值 -> 点右侧查看
        ((EditText) findViewById(R.id.etKey)).setOnClickListener(v -> onFieldTap(keyField, false));
        ((EditText) findViewById(R.id.etToken)).setOnClickListener(v -> onFieldTap(tokenField, false));

        // 右侧热区：无值 -> 录入；有值 -> 验证后显示
        findViewById(R.id.eyeKey).setOnClickListener(v -> onFieldTap(keyField, true));
        findViewById(R.id.eyeToken).setOnClickListener(v -> onFieldTap(tokenField, true));

        // 长按任意处：直接进入编辑（需验证）
        View.OnLongClickListener editEntry = v -> {
            Gate.request(this, new Gate.Callback() {
                @Override public void onGranted() { enterEditMode(); }
                @Override public void onDenied(String reason) { toast(reason); }
            });
            return true;
        };
        findViewById(R.id.eyeKey).setOnLongClickListener(editEntry);
        findViewById(R.id.eyeToken).setOnLongClickListener(editEntry);

        if (keyField.hasValue()) refreshBalance();
        if (tokenField.hasValue()) refreshUsage();
        if (!keyField.hasValue() && !tokenField.hasValue()) {
            tvStatus.setText("未设置密钥");
            tvHint.setText("点输入框即可录入密钥");
        } else {
            tvHint.setText("点右侧可查看明文 · 长按可修改");
        }

        findViewById(R.id.btnSave).setOnClickListener(v -> {
            if (!keyField.isEditing() && !tokenField.isEditing()) {
                toast("长按输入框右侧以进入编辑");
                return;
            }
            keyField.commitEdit();
            tokenField.commitEdit();
            try {
                if (keyField.hasValue()) BalanceApi.saveKey(this, keyField.getPlain());
                if (tokenField.hasValue()) BalanceApi.saveToken(this, tokenField.getPlain());
            } catch (Exception e) {
                toast("保存失败: " + e.getMessage());
                return;
            }
            if (keyField.hasValue()) refreshBalance();
            if (tokenField.hasValue()) refreshUsage();
            tvHint.setText("点右侧可查看明文 · 长按可修改");
            toast("已保存");
        });

        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (BalanceApi.loadKey(this) != null) refreshBalance();
            if (BalanceApi.loadToken(this) != null) refreshUsage();
        });

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            Gate.request(this, new Gate.Callback() {
                @Override public void onGranted() { doClear(); }
                @Override public void onDenied(String reason) { toast(reason); }
            });
        });
    }

    /** 点输入框或右侧热区：没值就去录入，有值且点的是热区才显示明文 */
    private void onFieldTap(SecureField f, boolean isEye) {
        if (f.isEditing()) return;   // 编辑态下不拦截
        if (!f.hasValue()) {
            Gate.request(this, new Gate.Callback() {
                @Override public void onGranted() { enterEditMode(); }
                @Override public void onDenied(String reason) { toast(reason); }
            });
            return;
        }
        if (!isEye) {
            toast("点右侧可查看明文，长按可修改");
            return;
        }
        gatedReveal(f);
    }

    private void gatedReveal(SecureField f) {
        if (!f.hasValue()) { toast("尚未设置"); return; }
        if (f.isRevealed()) { f.reveal(false); return; }
        Gate.request(this, new Gate.Callback() {
            @Override public void onGranted() {
                f.reveal(true);
                toast("20 秒后自动隐藏");
            }
            @Override public void onDenied(String reason) { toast(reason); }
        });
    }

    private void enterEditMode() {
        keyField.beginEdit();
        tokenField.beginEdit();
        // 焦点落到第一个空字段
        EditText target = (EditText) findViewById(
                keyField.hasValue() ? R.id.etToken : R.id.etKey);
        target.requestFocus();
        target.setSelection(target.getText().length());
        tvHint.setText("编辑中，完成后点“保存并查询”");
    }

    private void doClear() {
        BalanceApi.clearKey(this);
        BalanceApi.clearToken(this);
        SharedPreferences p = getSharedPreferences(BalanceApi.PREFS, 0);
        int theme = p.getInt("theme_mode", 0);   // 保留主题设置
        p.edit().clear().putInt("theme_mode", theme).apply();
        keyField.clear();
        tokenField.clear();
        tvBalance.setText("—");
        tvStatus.setText("已清除");
        tvDetail.setText("赠送余额 —\n充值余额 —");
        tvTime.setText("");
        tvInTokens.setText("—");
        tvOutTokens.setText("—");
        tvReqCount.setText("—");
        tvHitRate.setText("—%");
        tvCacheDetail.setText("");
        pbCache.setProgress(0);
        tvUsageStatus.setText("需填写平台 token");
        tvHint.setText("已清除，直接输入新的密钥");
        WidgetProvider.push(this, "—", "", "");
        toast("已清除");
        enterEditMode();   // 刚验证过，直接进编辑，不用再验一次
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Gate.onActivityResult(req, res, this);
    }

    // ---------- 余额 ----------
    private void refreshBalance() {
        String key = BalanceApi.loadKey(this);
        if (key == null) return;
        tvStatus.setText("查询中…");
        pool.execute(() -> {
            BalanceApi.Result r = BalanceApi.fetch(this, key);
            ui.post(() -> renderBalance(r));
        });
    }

    private void renderBalance(BalanceApi.Result r) {
        if (!r.ok) {
            tvStatus.setText("失败：" + r.error);
            tvBalance.setText("—");
            return;
        }
        String sym = BalanceApi.symbol(r.currency);
        tvBalance.setText(sym + r.total);
        tvStatus.setText(r.available ? "可用" : "不可用（余额不足）");
        tvDetail.setText("赠送余额 " + sym + r.granted + "\n充值余额 " + sym + r.toppedUp);
        String tm = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        tvTime.setText("更新于 " + tm);
        WidgetProvider.push(this, sym + r.total, r.currency, tm.substring(0, 5));
    }

    // ---------- 用量 ----------
    private void refreshUsage() {
        String tok = BalanceApi.loadToken(this);
        if (tok == null) return;
        tvUsageStatus.setText("查询中…");
        pool.execute(() -> {
            BalanceApi.Usage u = BalanceApi.fetchUsage(this, tok);
            ui.post(() -> renderUsage(u));
        });
    }

    private void renderUsage(BalanceApi.Usage u) {
        if (!u.ok) {
            tvUsageStatus.setText("失败：" + u.error);
            return;
        }
        WidgetProvider.refreshNow(this);
        tvInTokens.setText(BalanceApi.fmtTokens(u.hit + u.miss));
        tvOutTokens.setText(BalanceApi.fmtTokens(u.out));
        tvReqCount.setText(String.valueOf(u.requests));

        String s = u.month + " 月";
        if (u.requests == 0) s += " · 无调用记录";
        else s += " · 共 " + u.requests + " 次请求";
        tvUsageStatus.setText(s);

        long in = u.hit + u.miss;
        int pct = in > 0 ? (int) (u.hit * 100 / in) : 0;
        tvHitRate.setText(pct + "%");
        pbCache.setProgress(pct);

        long din = u.todayHit + u.todayMiss;
        int dpct = din > 0 ? (int) (u.todayHit * 100 / din) : 0;
        tvCacheDetail.setText("本月命中 " + BalanceApi.fmtTokens(u.hit)
                + " / 未命中 " + BalanceApi.fmtTokens(u.miss)
                + "\n今日命中 " + BalanceApi.fmtTokens(u.todayHit)
                + " / 未命中 " + BalanceApi.fmtTokens(u.todayMiss)
                + " / 输出 " + BalanceApi.fmtTokens(u.todayOut)
                + "\n今日命中率 " + dpct + "%  ·  " + u.todayReq + " 次请求");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
