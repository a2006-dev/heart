package com.xinji.heartbeat;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    public static final String REPO = "a2006-dev/heart";

    public static class Mirror {
        public final String name;
        public final String prefix;
        public long latencyMs;
        Mirror(String name, String prefix, long latency) {
            this.name = name; this.prefix = prefix; this.latencyMs = latency;
        }
    }

    public static final Mirror[] MIRRORS = {
        new Mirror("GitHub 直连", "https://github.com", 0),
        new Mirror("ghproxy.com", "https://ghproxy.com/https://github.com", 0),
        new Mirror("moeyy.cn", "https://github.moeyy.cn/https://github.com", 0),
        new Mirror("gh-proxy.com", "https://gh-proxy.com/https://github.com", 0),
        new Mirror("gh.idayer.com", "https://gh.idayer.com/https://github.com", 0),
        new Mirror("gh.ddlc.top", "https://gh.ddlc.top/https://github.com", 0),
        new Mirror("kkgithub.com", "https://kkgithub.com", 0)
    };

    public static void testAllMirrors() {
        for (Mirror mirror : MIRRORS) {
            try {
                String testUrl = mirror.prefix + "/" + REPO;
                long start = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(testUrl).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setInstanceFollowRedirects(false);
                conn.getResponseCode();
                conn.disconnect();
                mirror.latencyMs = System.currentTimeMillis() - start;
            } catch (Exception e) {
                mirror.latencyMs = -1;
            }
        }
    }

    public static String getCurrentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.3";
        }
    }

    public static String getDownloadUrl(String mirrorPrefix) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(mirrorPrefix + "/" + REPO + "/releases/latest").openConnection();
            conn.setConnectTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            conn.connect();
            String redirectUrl = conn.getHeaderField("Location");
            conn.disconnect();

            if (redirectUrl == null || !redirectUrl.contains("/releases/tag/")) return null;

            String tag = redirectUrl.substring(redirectUrl.indexOf("/releases/tag/") + "/releases/tag/".length());
            if (tag.contains("?")) tag = tag.substring(0, tag.indexOf("?"));

            return mirrorPrefix + "/" + REPO + "/releases/download/" + tag + "/heart-v2.3.apk";
        } catch (Exception e) {
            return null;
        }
    }

    public static String downloadApk(Context context, String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.connect();

            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }

            File cacheDir = new File(context.getCacheDir(), "update");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File apkFile = new File(cacheDir, "heart_update.apk");
            if (apkFile.exists()) apkFile.delete();

            FileOutputStream fos = new FileOutputStream(apkFile);
            byte[] buf = new byte[8192];
            int len;
            while ((len = conn.getInputStream().read(buf)) != -1) fos.write(buf, 0, len);
            fos.close();
            conn.disconnect();

            if (apkFile.length() < 100000) { apkFile.delete(); return null; }
            return apkFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    public static void installApk(Context context, String apkPath) {
        File apkFile = new File(apkPath);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        context.startActivity(intent);
    }
}
