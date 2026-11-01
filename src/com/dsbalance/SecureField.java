package com.dsbalance;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 一个“默认遮盖、验证后才短暂揭示”的密钥输入框。
 * 明文不在 View 里常驻，揭示超时后立即回填掩码。
 */
public class SecureField {

    private static final long REVEAL_MS = 20_000L;

    private final Activity act;
    private final EditText edit;
    private final String label;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String plain;      // 内存中的真实值
    private boolean revealed;  // 是否处于揭示状态
    private Runnable hideTask;

    public SecureField(Activity act, EditText edit, String label) {
        this.act = act;
        this.edit = edit;
        this.label = label;
        // 禁止软键盘直接编辑明文：输入框改为不可聚焦，值由程序回填
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setCursorVisible(false);
        // 点击行为由 Activity 统一接管
    }

    public String getPlain() {
        return plain;
    }

    public boolean hasValue() {
        return plain != null && !plain.isEmpty();
    }

    public void setPlain(String v) {
        this.plain = v;
        reveal(false);
    }

    /** 供“用户手动更换密钥”时用：进入编辑模式 */
    public void beginEdit() {
        editing = true;
        revealed = false;
        cancelHide();
        edit.setFocusableInTouchMode(true);
        edit.setFocusable(true);
        edit.setCursorVisible(true);
        edit.setText(plain == null ? "" : plain);
        edit.setSelection(edit.getText().length());
        edit.requestFocus();
        showKeyboard(edit);
    }

    public void commitEdit() {
        String v = edit.getText().toString().trim();
        plain = v.isEmpty() ? null : v;
        hideKeyboard(edit);
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setCursorVisible(false);
        editing = false;
        reveal(false);
    }

    public void clear() {
        plain = null;
        revealed = false;
        editing = false;
        cancelHide();
        edit.setFocusable(false);
        edit.setFocusableInTouchMode(false);
        edit.setCursorVisible(false);
        edit.setText("");
    }

    public void reveal(boolean on) {
        revealed = on;
        cancelHide();
        if (plain == null || plain.isEmpty()) {
            edit.setText("");
            return;
        }
        if (on) {
            edit.setFocusable(false);
            edit.setFocusableInTouchMode(false);
            edit.setText(plain);
            hideTask = () -> reveal(false);
            ui.postDelayed(hideTask, REVEAL_MS);
        } else {
            edit.setText(mask(plain));
        }
    }

    public boolean isRevealed() {
        return revealed;
    }

    private boolean editing;

    public boolean isEditing() {
        return editing;
    }

    private void cancelHide() {
        if (hideTask != null) {
            ui.removeCallbacks(hideTask);
            hideTask = null;
        }
    }

    /** sk-abc...xyz12 -> sk-abc********y12 */
    public static String mask(String k) {
        if (k == null || k.isEmpty()) return "";
        if (k.length() <= 8) return k.charAt(0) + "********";
        int head = Math.min(6, k.length() / 3);
        int tail = Math.min(4, k.length() / 4);
        StringBuilder sb = new StringBuilder();
        sb.append(k, 0, head);
        for (int i = 0; i < 8; i++) sb.append('*');
        sb.append(k.substring(k.length() - tail));
        return sb.toString();
    }

    private static void showKeyboard(EditText e) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        e.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private static void hideKeyboard(EditText e) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        e.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(e.getWindowToken(), 0);
    }
}
