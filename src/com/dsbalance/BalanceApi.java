package com.dsbalance;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BalanceApi {

    public static final String PREFS = "ds_balance";
    private static final String K_ENC = "key_enc";
    private static final String K_IV = "key_iv";
    private static final String T_ENC = "tok_enc";
    private static final String T_IV = "tok_iv";

    // ---------- 加解密 ----------
    private static SecretKeySpec deriveKey(Context c) throws Exception {
        String id = android.provider.Settings.Secure.getString(
                c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) id = "fallback-salt";
        byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(("dsbal:" + id + ":" + c.getPackageName()).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(d, "AES");
    }

    private static void store(Context c, String encKey, String ivKey, String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, deriveKey(c), new GCMParameterSpec(128, iv));
        byte[] ct = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        c.getSharedPreferences(PREFS, 0).edit()
                .putString(encKey, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply();
    }

    private static String restore(Context c, String encKey, String ivKey) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        String enc = p.getString(encKey, null);
        String ivs = p.getString(ivKey, null);
        if (enc == null || ivs == null) return null;
        try {
            byte[] iv = Base64.decode(ivs, Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, deriveKey(c), new GCMParameterSpec(128, iv));
            return new String(ci.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveKey(Context c, String k) throws Exception { store(c, K_ENC, K_IV, k); }
    public static String loadKey(Context c) { return restore(c, K_ENC, K_IV); }
    public static void clearKey(Context c) {
        c.getSharedPreferences(PREFS, 0).edit().remove(K_ENC).remove(K_IV).apply();
    }

    public static void saveToken(Context c, String t) throws Exception { store(c, T_ENC, T_IV, t); }
    public static String loadToken(Context c) { return restore(c, T_ENC, T_IV); }
    public static void clearToken(Context c) {
        c.getSharedPreferences(PREFS, 0).edit().remove(T_ENC).remove(T_IV).apply();
    }

    // ---------- 余额 ----------
    public static class Result {
        public boolean ok;
        public String total = "—", granted = "—", toppedUp = "—";
        public String currency = "CNY";
        public boolean available;
        public String error;
    }

    public static Result fetch(Context c, String apiKey) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = open("https://api.deepseek.com/user/balance", apiKey);
            int code = conn.getResponseCode();
            StringBuilder sb = read(conn, code);
            if (code == 401) { r.error = "Key 无效 (401)"; return r; }
            if (code != 200) { r.error = "HTTP " + code + " " + trunc(sb.toString(), 100); return r; }

            JSONObject o = new JSONObject(sb.toString());
            r.available = o.optBoolean("is_available", false);
            JSONArray arr = o.optJSONArray("balance_infos");
            if (arr != null && arr.length() > 0) {
                JSONObject b = arr.getJSONObject(0);
                r.currency = b.optString("currency", "CNY");
                r.total = b.optString("total_balance", "0");
                r.granted = b.optString("granted_balance", "0");
                r.toppedUp = b.optString("topped_up_balance", "0");
            }
            r.ok = true;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    // ---------- 用量（平台 userToken） ----------
    public static class Usage {
        public boolean ok;
        public long hit, miss, out, requests;
        public long todayHit, todayMiss, todayOut, todayReq;
        public String error;
        public int month, year;
    }

    public static Usage fetchUsage(Context c, String userToken) {
        Usage u = new Usage();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        u.month = cal.get(java.util.Calendar.MONTH) + 1;
        u.year = cal.get(java.util.Calendar.YEAR);
        String today = String.format(Locale.US, "%04d-%02d-%02d", u.year, u.month,
                cal.get(java.util.Calendar.DAY_OF_MONTH));

        HttpURLConnection conn = null;
        try {
            String url = "https://platform.deepseek.com/api/v0/usage/amount"
                    + "?month=" + u.month + "&year=" + u.year + "&tz=28800";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + userToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-client-platform", "web");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            int code = conn.getResponseCode();
            StringBuilder sb = read(conn, code);
            if (code == 401 || code == 403) { u.error = "token 失效 (" + code + ")"; return u; }
            if (code != 200) { u.error = "HTTP " + code + " " + trunc(sb.toString(), 100); return u; }

            JSONObject root = new JSONObject(sb.toString());
            if (root.optInt("code", -1) != 0) {
                u.error = root.optString("msg", "接口返回异常");
                return u;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) { u.error = "无 data"; return u; }
            int bizCode = data.optInt("biz_code", -1);
            if (bizCode != 0) {
                u.error = "biz " + bizCode + ": " + data.optString("biz_msg", "");
                return u;
            }
            JSONObject biz = data.optJSONObject("biz_data");
            if (biz == null) { u.error = "无 biz_data"; return u; }

            sumModels(u, biz.optJSONArray("total"), true);

            JSONArray days = biz.optJSONArray("days");
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject d = days.optJSONObject(i);
                    if (d == null) continue;
                    if (today.equals(d.optString("date", ""))) {
                        sumModels(u, d.optJSONArray("data"), false);
                        break;
                    }
                }
            }
            u.ok = true;
        } catch (Exception e) {
            u.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return u;
    }

    private static void sumModels(Usage u, JSONArray models, boolean month) {
        if (models == null) return;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            JSONArray arr = m.optJSONArray("usage");
            if (arr == null) continue;
            for (int j = 0; j < arr.length(); j++) {
                JSONObject it = arr.optJSONObject(j);
                if (it == null) continue;
                String type = it.optString("type", "");
                long amt = 0;
                try { amt = Long.parseLong(it.optString("amount", "0")); } catch (Exception ignore) {}
                switch (type) {
                    case "PROMPT_CACHE_HIT_TOKEN":
                        if (month) u.hit += amt; else u.todayHit += amt; break;
                    case "PROMPT_CACHE_MISS_TOKEN":
                        if (month) u.miss += amt; else u.todayMiss += amt; break;
                    case "PROMPT_TOKEN":
                        if (month) u.miss += amt; else u.todayMiss += amt; break;
                    case "RESPONSE_TOKEN":
                        if (month) u.out += amt; else u.todayOut += amt; break;
                    case "REQUEST":
                        if (month) u.requests += amt; else u.todayReq += amt; break;
                    default: break;
                }
            }
        }
    }

    private static long num(JSONObject o, String k, long def) {
        try { return o.optLong(k, def); } catch (Exception e) { return def; }
    }


    // ---------- 对话调用（记录 usage 与缓存命中） ----------
    public static class Chat {
        public boolean ok;
        public String reply = "";
        public long hit, miss, out;
        public String error;
    }

    public static Chat chat(Context c, String apiKey, String model, String prompt) {
        Chat r = new Chat();
        HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL("https://api.deepseek.com/chat/completions");
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);

            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            body.put("stream", false);
            org.json.JSONArray msgs = new org.json.JSONArray();
            msgs.put(new org.json.JSONObject().put("role", "user").put("content", prompt));
            body.put("messages", msgs);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream os = conn.getOutputStream()) { os.write(payload); }

            int code = conn.getResponseCode();
            StringBuilder sb = read(conn, code);
            if (code == 401) { r.error = "Key 无效 (401)"; return r; }
            if (code == 402) { r.error = "余额不足 (402)"; return r; }
            if (code == 429) { r.error = "请求过于频繁 (429)"; return r; }
            if (code != 200) { r.error = "HTTP " + code + " " + trunc(sb.toString(), 120); return r; }

            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            org.json.JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                org.json.JSONObject msg = ch.getJSONObject(0).optJSONObject("message");
                if (msg != null) r.reply = msg.optString("content", "");
            }
            org.json.JSONObject us = o.optJSONObject("usage");
            if (us != null) {
                r.hit = us.optLong("prompt_cache_hit_tokens", 0);
                r.miss = us.optLong("prompt_cache_miss_tokens", 0);
                r.out = us.optLong("completion_tokens", 0);
                if (r.hit == 0 && r.miss == 0) {
                    long pt = us.optLong("prompt_tokens", 0);
                    r.miss = pt - r.hit;
                }
            }
            r.ok = true;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    public static String loadModel(Context c) {
        return c.getSharedPreferences(PREFS, 0).getString("model", "deepseek-v4-flash");
    }

    public static void saveModel(Context c, String m) {
        c.getSharedPreferences(PREFS, 0).edit().putString("model", m).apply();
    }

    // ---------- 本地缓存命中累计 ----------
    public static final String[] CACHE_KEYS = {"c_hit", "c_miss", "c_out", "c_req"};

    public static void addCache(Context c, long hit, long miss, long out, long req) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (!today.equals(p.getString("c_day", ""))) {
            p.edit().putString("c_day", today)
                    .putLong(CACHE_KEYS[0], 0).putLong(CACHE_KEYS[1], 0)
                    .putLong(CACHE_KEYS[2], 0).putLong(CACHE_KEYS[3], 0).apply();
        }
        p.edit()
                .putLong(CACHE_KEYS[0], p.getLong(CACHE_KEYS[0], 0) + hit)
                .putLong(CACHE_KEYS[1], p.getLong(CACHE_KEYS[1], 0) + miss)
                .putLong(CACHE_KEYS[2], p.getLong(CACHE_KEYS[2], 0) + out)
                .putLong(CACHE_KEYS[3], p.getLong(CACHE_KEYS[3], 0) + req)
                .apply();
    }

    public static long[] getCache(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        return new long[]{p.getLong(CACHE_KEYS[0], 0), p.getLong(CACHE_KEYS[1], 0),
                p.getLong(CACHE_KEYS[2], 0), p.getLong(CACHE_KEYS[3], 0)};
    }

    // ---------- HTTP 帮助 ----------
    private static HttpURLConnection open(String url, String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private static StringBuilder read(HttpURLConnection conn, int code) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return sb;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb;
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public static String symbol(String cur) {
        if ("USD".equalsIgnoreCase(cur)) return "$";
        if ("CNY".equalsIgnoreCase(cur)) return "¥";
        return cur + " ";
    }

    public static String fmtTokens(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.valueOf(n);
    }
}
