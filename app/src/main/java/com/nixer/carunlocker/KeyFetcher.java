package com.nixer.carunlocker;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 在本机上完成：短信验证码登录 → 换 zone token → 申请钥匙 → 下载解密 → 写入 AppPrefs。
 * 不依赖另一台电脑；验证码和钥匙材料只在手机内使用。
 */
public final class KeyFetcher {
    public interface Callback {
        void onProgress(String msg);
        void onDone(boolean success, String detail);
    }

    private static final String LOGIN_URL = "https://capi-pv.saicmotor.com/app-mp/uais/1.0/quickLoginByVcodeV2";
    private static final String ZONE_URL = "https://capi-pv.saicmotor.com/app-mp/user/1.1/getOutSideServiceToken";
    private static final String VC_URL = "https://app-vc.z-onesoft.com";
    private static final String PATH_QUERY_KEY = "/app/cid/key/QueryOrCreateNxKey";
    private static final String PATH_DOWNLOAD_KEY = "/app/cid/key/downloadKey";

    private static final String P256_X509_PREFIX =
            "3059301306072A8648CE3D020106082A8648CE3D030107034200";
    private static final BigInteger CURVE_P = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger CURVE_A = CURVE_P.subtract(BigInteger.valueOf(3));
    private static final BigInteger CURVE_B = new BigInteger(
            "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16);
    private static final byte[] AES_IV = range16();
    private static final String NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final Context ctx;
    private final String phone;
    private final String vcode;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 本次登录使用的设备标识；为空时自动生成并持久化
    private String uuid;
    private String deviceId;
    private String vin;

    public KeyFetcher(Context ctx, String phone, String vcode, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.phone = phone;
        this.vcode = vcode;
        this.cb = cb;
    }

    public void start() {
        new Thread(this::run, "key-fetcher").start();
    }

    private void progress(String msg) {
        main.post(() -> cb.onProgress(msg));
    }

    private void done(boolean ok, String detail) {
        main.post(() -> cb.onDone(ok, detail));
    }

    private void run() {
        try {
            if (TextUtils.isEmpty(BuildConfig.VC_APP_KEY) || TextUtils.isEmpty(BuildConfig.VC_PKSK_HEX)) {
                throw new IllegalStateException("本机 local.properties 缺少 APP_KEY/APP_SECRET/PKSK_HEX，无法登录取钥匙");
            }
            vin = nonEmpty(BuildConfig.SAIC_VIN, AppPrefs.get(ctx, AppPrefs.K_SAIC_VIN));
            // uuid 可以随机生成；deviceId 决定钥匙归属，首次登录时生成并保存
            uuid = nonEmpty(BuildConfig.SAIC_UUID, AppPrefs.get(ctx, AppPrefs.K_SAIC_UUID));
            if (TextUtils.isEmpty(uuid)) {
                uuid = randomHex(20);
                AppPrefs.set(ctx, AppPrefs.K_SAIC_UUID, uuid);
            }
            deviceId = nonEmpty(BuildConfig.SAIC_DEVICE_ID, AppPrefs.get(ctx, AppPrefs.K_SAIC_DEVICE_ID));
            if (TextUtils.isEmpty(deviceId)) {
                deviceId = randomHex(16);
                AppPrefs.set(ctx, AppPrefs.K_SAIC_DEVICE_ID, deviceId);
            }
            progress("1/5 短信验证码登录…");
            JSONObject loginBody = new JSONObject();
            loginBody.put("appType", "mp4roeweapp");
            loginBody.put("app_version", "上汽荣威_3.3.8");
            loginBody.put("deviceId", "");
            loginBody.put("mobile_brand", "google");
            loginBody.put("mobile_type", "sdk_gphone_x86_64");
            loginBody.put("os_version", "13");
            loginBody.put("phone", phone);
            loginBody.put("phoneName", "");
            loginBody.put("platformType", "Android");
            loginBody.put("sid", "");
            loginBody.put("uuid", uuid);
            loginBody.put("vcode", vcode);
            JSONObject login = new JSONObject(postForm(LOGIN_URL, loginBody.toString()));
            JSONObject loginData = login.optJSONObject("data");
            if (loginData == null || !loginData.optBoolean("loginResult")) {
                throw new IllegalStateException("登录失败：" + brief(login.toString()));
            }
            String toc = loginData.optJSONObject("loginData").optString("token");
            if (TextUtils.isEmpty(toc)) throw new IllegalStateException("登录响应没有 token");

            if (!TextUtils.isEmpty(BuildConfig.BANMA_SIGN_KEY)) {
                progress("自动获取账号车辆 VIN…");
                String fetched = fetchVin(toc, uuid, deviceId);
                if (!TextUtils.isEmpty(fetched)) {
                    AppPrefs.set(ctx, AppPrefs.K_SAIC_VIN, fetched);
                    progress("已自动获取车架号 VIN");
                    if (TextUtils.isEmpty(vin)) {
                        vin = fetched;
                    } else if (!fetched.equalsIgnoreCase(vin)) {
                        progress("账号车辆与配置 VIN 不一致，继续使用配置值");
                    }
                }
            }
            if (TextUtils.isEmpty(vin)) vin = AppPrefs.get(ctx, AppPrefs.K_SAIC_VIN);
            if (TextUtils.isEmpty(vin)) {
                throw new IllegalStateException("无法自动获取 VIN；请在 local.properties 配置 SAIC_VIN 兜底");
            }
            progress("2/5 登录成功，换取 zone token…");

            JSONObject zoneBody = new JSONObject();
            zoneBody.put("brandCode", "1");
            zoneBody.put("deviceId", deviceId);
            zoneBody.put("token", toc);
            zoneBody.put("types", new JSONArray().put(1).put(2));
            JSONObject zone = new JSONObject(postForm(ZONE_URL, zoneBody.toString()));
            JSONObject zoneData = zone.optJSONObject("data");
            String zoneToken = zoneData == null ? "" : zoneData.optString("zoneAccessToken").replace("Bearer ", "");
            String userId = zoneData == null ? "" : zoneData.optString("zoneUserId");
            if (TextUtils.isEmpty(userId)) userId = BuildConfig.SAIC_USER_ID;
            if (TextUtils.isEmpty(zoneToken)) {
                throw new IllegalStateException("换 zone token 失败：" + brief(zone.toString()));
            }
            progress("3/5 申请钥匙（会顶掉旧钥匙）…");

            JSONObject queryBody = new JSONObject();
            queryBody.put("phoneModel", "NX769J");
            queryBody.put("phoneBrand", "nubia");
            queryBody.put("vin", vin);
            queryBody.put("deviceId", deviceId);
            HttpResp queryResp = postZone(PATH_QUERY_KEY, queryBody.toString(), zoneToken);
            JSONObject query = new JSONObject(queryResp.body);
            JSONObject queryData = query.optJSONObject("data");
            String keyId = queryData == null ? "" : queryData.optString("keyId");
            if (TextUtils.isEmpty(keyId)) {
                throw new IllegalStateException("申请钥匙失败 HTTP " + queryResp.code + "：" + brief(queryResp.body));
            }
            progress("4/5 下载并解密钥匙… " + safeKeyId(keyId));

            KeyPair ephemeral = generateP256();
            String ptpk = hex(rawPublicPoint((ECPublicKey) ephemeral.getPublic()));
            byte[] signed = signWithPksk(concat(hexToBytes(ptpk),
                    (vin + userId + keyId).getBytes(StandardCharsets.UTF_8)));
            JSONObject dlBody = new JSONObject();
            dlBody.put("ptpks", hex(signed));
            dlBody.put("keyId", keyId);
            dlBody.put("ptpk", ptpk);
            HttpResp dlResp = postZone(PATH_DOWNLOAD_KEY, dlBody.toString(), zoneToken);
            JSONObject dl = new JSONObject(dlResp.body);
            JSONObject dlData = dl.optJSONObject("data");
            String ctpk = dlData == null ? "" : dlData.optString("ctpk");
            String encKey = dlData == null ? "" : dlData.optString("s");
            if (TextUtils.isEmpty(ctpk) || TextUtils.isEmpty(encKey)) {
                throw new IllegalStateException("下载钥匙失败 HTTP " + dlResp.code + "：" + brief(dlResp.body));
            }

            PublicKey server = publicKeyFromRawPoint(hexToBytes(ctpk));
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ephemeral.getPrivate());
            agreement.doPhase(server, true);
            byte[] shared = agreement.generateSecret();
            String plain = decryptKey(encKey, shared);
            JSONObject key = new JSONObject(plain);

            String ks = nonEmpty(key.optString("ks"), key.optString("BLE_KS"));
            String kr = nonEmpty(key.optString("krHex"), key.optString("kr"), key.optString("KR"));
            String mac = normalizeMac(nonEmpty(key.optString("bleMac"), key.optString("mac")));
            String name = nonEmpty(key.optString("bleName"), key.optString("name"));
            if (TextUtils.isEmpty(ks) || TextUtils.isEmpty(kr)) {
                throw new IllegalStateException("钥匙 JSON 缺字段：" + brief(plain));
            }

            progress("5/5 写入本机钥匙…");
            AppPrefs.set(ctx, AppPrefs.K_BLE_KS, ks);
            AppPrefs.set(ctx, AppPrefs.K_BLE_KEY_ID, keyId);
            AppPrefs.set(ctx, AppPrefs.K_BLE_KR, kr);
            if (!TextUtils.isEmpty(mac)) {
                AppPrefs.set(ctx, AppPrefs.K_BLE_MAC, mac);
                AppPrefs.set(ctx, AppPrefs.K_TARGET_MAC, mac);
                if (!TextUtils.isEmpty(name)) {
                    AppPrefs.set(ctx, AppPrefs.K_TARGET_NAME, name);
                }
            }
            AppPrefs.set(ctx, AppPrefs.K_KEY_SOURCE,
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date())
                            + " 短信登录获取 " + safeKeyId(keyId));
            done(true, "钥匙已获取并保存（" + safeKeyId(keyId) + "），请重启守护服务后使用");
        } catch (Throwable t) {
            done(false, t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private static String safeKeyId(String keyId) {
        if (TextUtils.isEmpty(keyId)) return "";
        return keyId.length() <= 10 ? keyId : keyId.substring(0, 10) + "…";
    }

    private static String nonEmpty(String... values) {
        for (String v : values) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() <= 320 ? s : s.substring(0, 320) + "…";
    }

    // ---------------- 账号车辆列表（自动获取 VIN） ----------------

    private String fetchVin(String toc, String uuid, String deviceId) {
        if (TextUtils.isEmpty(BuildConfig.BANMA_SIGN_KEY)) {
            progress("未配置 BANMA_SIGN_KEY，跳过自动获取 VIN");
            return "";
        }
        String path = "/car/api/app/getSimpleVehicleInfo";
        String body = "{}";
        String ts = String.valueOf(System.currentTimeMillis());
        HttpURLConnection conn = null;
        try {
            String sign = banmaSign("roewe", path, ts, body, BuildConfig.BANMA_SIGN_KEY);
            conn = (HttpURLConnection) new URL("https://mp.ebanma.com" + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Uuid", uuid);
            conn.setRequestProperty("DeviceID", deviceId);
            conn.setRequestProperty("token", toc);
            conn.setRequestProperty("brandCode", "1");
            conn.setRequestProperty("timestamp", ts);
            conn.setRequestProperty("os", "android");
            conn.setRequestProperty("osVersion", String.valueOf(Build.VERSION.SDK_INT));
            conn.setRequestProperty("x-client-id", "APP");
            conn.setRequestProperty("versionCode", "1");
            conn.setRequestProperty("app-type", "roewe");
            conn.setRequestProperty("sign", sign);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String resp = in == null ? "" : readStream(in);
            if (code == 200 && !TextUtils.isEmpty(resp)) {
                String vin = findVin(new JSONObject(resp));
                if (!TextUtils.isEmpty(vin)) return vin;
            }
            progress("车辆列表 HTTP " + code + (TextUtils.isEmpty(resp) ? "" : "：" + brief(maskVin(resp))));
        } catch (Throwable t) {
            progress("自动获取 VIN 失败：" + (t.getMessage() == null ? t.toString() : t.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    /** 与官方 App 的 BanmaRequestSignInterceptor/SignUtils 一致：排序后拼接，HmacSHA256，Base64(NO_WRAP)。 */
    private static String banmaSign(String appType, String path, String ts, String data, String key) throws Exception {
        ArrayList<String> parts = new ArrayList<>();
        parts.add(path);
        parts.add(ts);
        parts.add(data);
        parts.add(appType);
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String findVin(Object o) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                if (v instanceof String && k.toLowerCase(Locale.ROOT).contains("vin")) {
                    String s = (String) v;
                    if (isVin(s)) return s;
                }
                String r = findVin(v);
                if (!TextUtils.isEmpty(r)) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = findVin(a.opt(i));
                if (!TextUtils.isEmpty(r)) return r;
            }
        } else if (o instanceof String && isVin((String) o)) {
            return (String) o;
        }
        return "";
    }

    private static boolean isVin(String s) {
        return s != null && s.matches("[A-HJ-NPR-Z0-9]{17}");
    }

    private static String maskVin(String s) {
        return s == null ? "" : s.replaceAll("[A-HJ-NPR-Z0-9]{17}", "***");
    }

    // ---------------- 网络 ----------------

    private static String postForm(String url, String json) throws IOException {
        String form = "data=" + URLEncoder.encode(json, "UTF-8")
                + "&brandCode=1&timestamp=" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = conn.getOutputStream();
        os.write(form.getBytes(StandardCharsets.UTF_8));
        os.close();
        return readResponse(conn);
    }

    private static final class HttpResp {
        final int code;
        final String body;
        HttpResp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private HttpResp postZone(String path, String json, String zoneToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(VC_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(20_000);
        Map<String, String> headers = zoneHeaders(path, zoneToken);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        OutputStream os = conn.getOutputStream();
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readStream(in);
        conn.disconnect();
        return new HttpResp(code, body);
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = readStream(in);
        conn.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + "：" + brief(body));
        return body;
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private Map<String, String> zoneHeaders(String path, String token) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = ts + randomNonce(20);
        String canonical = nonce + "\n" + ts + "\nPOST\n" + path + "\n" + BuildConfig.VC_APP_KEY;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(BuildConfig.VC_APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sign = Base64.encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);

        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=UTF-8");
        h.put("x-appkey", BuildConfig.VC_APP_KEY);
        h.put("x-nonce", nonce);
        h.put("x-timestamp", ts);
        h.put("x-sign", sign);
        h.put("path", path);
        h.put("x-token", "Bearer " + token);
        h.put("vin", vin);
        h.put("t-deviceid", deviceId);
        h.put("t-devicetype", "2");
        h.put("t-deviceversion", "16");
        h.put("t-devicename", "NX769J");
        h.put("t-tenantcode", "R_Tenant");
        h.put("t-channel", "APP");
        h.put("t-osversion", "1");
        h.put("t-usertype", "2");
        h.put("t-username", "yzc");
        h.put("t-name", "R_Tag");
        h.put("t-devicescreen", "1");
        h.put("t-platform", "Android");
        h.put("t_vehicletype", "1");
        h.put("t-brand", "%E8%8D%A3%E5%A8%81");
        h.put("os-version", "111");
        h.put("x-source", "app");
        h.put("x-v", "4.0.0");
        h.put("sdk-version-name", "1.0.0");
        h.put("sdk-version-code", "1240");
        return h;
    }

    private static String randomNonce(int len) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(NONCE_ALPHABET.charAt(rnd.nextInt(NONCE_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ---------------- 密码学 ----------------

    private static KeyPair generateP256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return g.generateKeyPair();
    }

    private static byte[] signWithPksk(byte[] msg) throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        java.security.PrivateKey priv = KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new BigInteger(BuildConfig.VC_PKSK_HEX, 16), spec));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(priv);
        sig.update(msg);
        return sig.sign(); // DER，与 Python cryptography 默认输出一致
    }

    private static byte[] rawPublicPoint(ECPublicKey key) {
        return concat(new byte[]{0x04},
                to32(key.getW().getAffineX()), to32(key.getW().getAffineY()));
    }

    private static PublicKey publicKeyFromRawPoint(byte[] raw) throws Exception {
        byte[] x;
        byte[] y;
        if (raw.length == 65 && raw[0] == 0x04) {
            x = Arrays.copyOfRange(raw, 1, 33);
            y = Arrays.copyOfRange(raw, 33, 65);
        } else if ((raw[0] == 0x02 || raw[0] == 0x03) && raw.length == 33) {
            x = Arrays.copyOfRange(raw, 1, 33);
            y = recoverY(x, (raw[0] & 1) == 1);
        } else {
            throw new IllegalStateException("无法识别服务端公钥点长度=" + raw.length + " 首字节=" + raw[0]);
        }
        byte[] x509 = concat(hexToBytes(P256_X509_PREFIX), new byte[]{0x04}, x, y);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
    }

    private static byte[] recoverY(byte[] xBytes, boolean odd) {
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger y2 = x.pow(3).mod(CURVE_P)
                .add(CURVE_A.multiply(x).mod(CURVE_P)).mod(CURVE_P)
                .add(CURVE_B).mod(CURVE_P);
        BigInteger y = y2.modPow(CURVE_P.add(BigInteger.ONE).shiftRight(2), CURVE_P);
        if (y.testBit(0) != odd) y = CURVE_P.subtract(y);
        return to32(y);
    }

    private static String decryptKey(String sHex, byte[] sharedX) throws Exception {
        byte[] x = to32(new BigInteger(1, sharedX));
        for (boolean odd : new boolean[]{false, true}) {
            byte[] point = concat(new byte[]{0x04}, x, recoverY(x, odd));
            byte[] aesKey = MessageDigest.getInstance("SHA-256").digest(point);
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(AES_IV));
                byte[] plain = cipher.doFinal(hexToBytes(sHex));
                String text = new String(plain, StandardCharsets.UTF_8);
                if (text.trim().startsWith("{")) return text;
            } catch (Throwable ignored) {
                // 试另一个 y
            }
        }
        throw new IllegalStateException("ECDH 解密失败");
    }

    // ---------------- 基本工具 ----------------

    private static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 32) b = Arrays.copyOfRange(b, b.length - 32, b.length);
        byte[] out = new byte[32];
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
        return out;
    }

    private static byte[] range16() {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) i;
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format(Locale.ROOT, "%02x", v & 0xFF));
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    private static byte[] hexToBytes(String s) {
        if (s == null || (s.length() & 1) != 0) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String normalizeMac(String raw) {
        if (raw == null) return "";
        String s = raw.replace(":", "").replace("-", "").replace(" ", "").toUpperCase();
        if (s.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(s, i, i + 2);
        }
        return sb.toString();
    }

}
