package com.dsbalance;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * 查看密钥前的身份验证：优先生物识别，没有指纹/面容时退回设备 PIN/图案/密码。
 * 使用 Keystore 强绑定，验证通过后才能解密一次。
 */
public class Gate {

    public interface Callback {
        void onGranted();
        void onDenied(String reason);
    }

    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "ds_balance_gate";

    // ---------- 密钥 ----------
    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return (SecretKey) ks.getKey(ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R - 1) {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    /** 能否使用生物识别；不能则只能走设备凭据 */
    public static boolean hasBiometric(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        BiometricManager bm = c.getSystemService(BiometricManager.class);
        if (bm == null) return false;
        int r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        return r == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** 发起验证 */
    public static void request(Activity a, Callback cb) {
        if (!deviceSecure(a)) {
            cb.onDenied("设备未设置锁屏密码");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // 8.0/8.1 退回 KEYGUARD 解锁
            requireDeviceCredential(a, cb);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKey key = getOrCreateKey();
            cipher.init(Cipher.ENCRYPT_MODE, key);
            showPrompt(a, cipher, cb, false);
        } catch (Exception e) {
            // Keystore 失效（换指纹、清除凭据）则重建
            try {
                KeyStore ks = KeyStore.getInstance(KS);
                ks.load(null);
                ks.deleteEntry(ALIAS);
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                showPrompt(a, cipher, cb, false);
                return;
            } catch (Exception e2) {
                requireDeviceCredential(a, cb);
            }
        }
    }

    private static void showPrompt(Activity a, Cipher cipher, Callback cb, boolean allowCredential) {
        CancellationSignal cs = new CancellationSignal();

        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a)
                .setTitle("验证身份")
                .setSubtitle("验证后临时显示密钥内容")
                .setNegativeButton("取消", a.getMainExecutor(),
                        (dialog, which) -> cb.onDenied("已取消"));

        if (hasBiometric(a)) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            // 没有生物识别：让系统自己弹 PIN
            requireDeviceCredential(a, cb);
            return;
        }

        BiometricPrompt prompt = b.build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), cs, a.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        cb.onGranted();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        cb.onDenied(msg == null ? "验证失败" : msg.toString());
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // 单次失败，系统会让用户重试
                    }
                });
    }

    private static void requireDeviceCredential(Activity a, Callback cb) {
        KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            cb.onDenied("设备未设置锁屏密码");
            return;
        }
        android.content.Intent i = km.createConfirmDeviceCredentialIntent(
                "验证身份", "验证后临时显示密钥内容");
        a.startActivityForResult(i, REQ_CRED);
        pending = cb;
        pendingActivity = a;
    }

    private static final int REQ_CRED = 0x51;
    private static Callback pending;
    private static Activity pendingActivity;

    /** 在 onActivityResult 里调用 */
    public static boolean onActivityResult(int requestCode, int resultCode, Activity a) {
        if (requestCode != REQ_CRED) return false;
        Callback cb = pending;
        pending = null;
        pendingActivity = null;
        if (cb == null) return true;
        if (resultCode == Activity.RESULT_OK) cb.onGranted();
        else cb.onDenied("已取消");
        return true;
    }
}
