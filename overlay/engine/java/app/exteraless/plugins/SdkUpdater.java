package app.exteraless.plugins;

import android.content.Context;
import android.util.Base64;

import com.chaquo.python.Python;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updatable pure-Python SDK. The baseline ships inside the APK; a newer signed zip is fetched over HTTPS,
 * verified (SHA-256 + ECDSA P-256), unpacked at the next start and put in front of sys.path.
 * A start that never finishes drops the override, so a bad SDK costs one launch.
 */
public final class SdkUpdater {

    public static final String BASELINE_VERSION = "1.4.6.3";

    private static final String MANIFEST_URL = "https://github.com/entinyGram/plugins/releases/download/python-sdk/sdk.json";
    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhxlZOtbSJXw3XAaZ08jXlchoI7wpdkp7HRriupKaLAPkOGtlWTZfM463I1hH0QXF5SA3+WjGZJLv3kvvH6MLtQ==";
    private static final String PAYLOAD_PREFIX = "entiny-pysdk|";

    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long MAX_ZIP_BYTES = 32L * 1024 * 1024;
    private static final long MAX_UNPACKED_BYTES = 64L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");
    private static final String[] ALLOWED_EXTENSIONS = {".py", ".txt", ".json"};

    private static volatile String activeVersion = BASELINE_VERSION;
    private static volatile boolean checking;

    private SdkUpdater() {
    }

    public static String getActiveVersion() {
        return activeVersion;
    }

    private static File root(Context context) {
        return new File(context.getFilesDir(), "entiny-sdk");
    }

    /** Before the SDK modules are first imported: applies a pending update, then puts the override on sys.path. */
    public static synchronized void activate(Context context) {
        try {
            File root = root(context);
            File booting = new File(root, "booting");
            File current = new File(root, "current");
            if (booting.exists()) {
                String bad = readVersion(current);
                FileLog.e("SdkUpdater: the previous start with SDK " + bad + " never finished, back to the baseline");
                if (bad != null) {
                    write(new File(root, "rejected"), bad);
                }
                deleteRecursive(current);
                deleteRecursive(new File(root, "pending"));
                booting.delete();
                return;
            }
            installPending(root);
            String version = readVersion(current);
            if (version == null || compare(version, BASELINE_VERSION) <= 0) {
                return;
            }
            if (!root.isDirectory() && !root.mkdirs()) {
                return;
            }
            write(booting, version);
            Python.getInstance().getModule("sys").get("path").callAttr("insert", 0, current.getAbsolutePath());
            activeVersion = version;
            FileLog.d("SdkUpdater: SDK " + version + " is active");
        } catch (Throwable t) {
            FileLog.e("SdkUpdater: activation failed, the baseline stays", t);
        }
    }

    /** The interpreter is up and the loader imported: the override is good. */
    public static void onStarted(Context context) {
        try {
            new File(root(context), "booting").delete();
        } catch (Throwable ignored) {
        }
        checkForUpdate(context);
    }

    public static void checkForUpdate(Context context) {
        if (checking) {
            return;
        }
        final Context app = context.getApplicationContext();
        File stamp = new File(root(app), "last_check");
        if (stamp.exists() && System.currentTimeMillis() - stamp.lastModified() < CHECK_INTERVAL_MS) {
            return;
        }
        checking = true;
        Thread thread = new Thread(() -> {
            try {
                fetch(app);
            } catch (Throwable t) {
                FileLog.e("SdkUpdater: update check failed", t);
            } finally {
                checking = false;
            }
        }, "SdkUpdater");
        thread.setDaemon(true);
        thread.start();
    }

    private static void fetch(Context context) throws Exception {
        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs()) {
            return;
        }
        write(new File(root, "last_check"), String.valueOf(System.currentTimeMillis()));
        JSONObject manifest = new JSONObject(new String(download(MANIFEST_URL, 256 * 1024, null), StandardCharsets.UTF_8));
        JSONObject entry = manifest.optJSONObject("stable");
        if (entry == null) {
            return;
        }
        String version = entry.getString("version");
        String sha256 = entry.getString("sha256");
        String signature = entry.getString("signature");
        String url = entry.getString("url");
        if (!url.startsWith("https://") || !isVersion(version)) {
            return;
        }
        String known = readVersion(new File(root, "current"));
        if (compare(version, BASELINE_VERSION) <= 0 || (known != null && compare(version, known) <= 0)) {
            return;
        }
        String rejected = read(new File(root, "rejected"));
        if (version.equals(rejected)) {
            return;
        }
        File pending = new File(root, "pending");
        String queued = queuedVersion(pending);
        if (queued != null && compare(version, queued) <= 0) {
            return;
        }
        deleteRecursive(pending);
        if (!pending.mkdirs()) {
            return;
        }
        File zip = new File(pending, "sdk.zip");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            download(url, MAX_ZIP_BYTES, zip, digest);
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(sha256) || !verify(version, actual, signature)) {
                throw new SecurityException("SDK " + version + " failed verification");
            }
            JSONObject meta = new JSONObject().put("version", version).put("sha256", actual).put("signature", signature);
            write(new File(pending, "meta.json"), meta.toString());
            FileLog.d("SdkUpdater: SDK " + version + " downloaded, it applies at the next start");
        } catch (Throwable t) {
            deleteRecursive(pending);
            throw t;
        }
    }

    private static void installPending(File root) {
        File pending = new File(root, "pending");
        File zip = new File(pending, "sdk.zip");
        File metaFile = new File(pending, "meta.json");
        if (!zip.isFile() || !metaFile.isFile()) {
            deleteRecursive(pending);
            return;
        }
        File staging = new File(root, "current.tmp");
        try {
            JSONObject meta = new JSONObject(read(metaFile));
            String version = meta.getString("version");
            if (!isVersion(version)) {
                throw new SecurityException("bad version");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(zip)) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                }
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(meta.getString("sha256")) || !verify(version, actual, meta.getString("signature"))) {
                throw new SecurityException("SDK " + version + " failed verification");
            }
            deleteRecursive(staging);
            unpack(zip, staging);
            if (!new File(staging, "base_plugin.py").isFile()) {
                throw new IOException("base_plugin.py is missing");
            }
            write(new File(staging, "version.txt"), version);
            File current = new File(root, "current");
            deleteRecursive(current);
            if (!staging.renameTo(current)) {
                throw new IOException("could not move the SDK into place");
            }
            FileLog.d("SdkUpdater: SDK " + version + " installed");
        } catch (Throwable t) {
            FileLog.e("SdkUpdater: dropping the pending SDK", t);
            deleteRecursive(staging);
        } finally {
            deleteRecursive(pending);
        }
    }

    private static void unpack(File zip, File destination) throws IOException {
        if (!destination.mkdirs()) {
            throw new IOException("cannot create " + destination);
        }
        String base = destination.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("too many entries");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!SAFE_NAME.matcher(name).matches() || name.contains("..") || name.contains("//") || !hasAllowedExtension(name)) {
                    throw new SecurityException("unexpected entry " + name);
                }
                File out = new File(destination, name);
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new SecurityException("entry escapes the SDK folder: " + name);
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                long size = 0;
                try (OutputStream stream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[16384];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        size += n;
                        total += n;
                        if (size > MAX_FILE_BYTES || total > MAX_UNPACKED_BYTES) {
                            throw new IOException("SDK is too large");
                        }
                        stream.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static boolean verify(String version, String sha256, String signatureBase64) throws Exception {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update((PAYLOAD_PREFIX + version + "|" + sha256.toLowerCase()).getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.decode(signatureBase64, Base64.DEFAULT));
    }

    private static byte[] download(String url, long limit, File target) throws IOException {
        return download(url, limit, target, null);
    }

    private static byte[] download(String url, long limit, File target, MessageDigest digest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "entinyGram-sdk");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
            }
            ByteArrayOutputStream memory = target == null ? new ByteArrayOutputStream() : null;
            long total = 0;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = target != null ? new FileOutputStream(target) : memory) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > limit) {
                        throw new IOException("download is larger than " + limit);
                    }
                    if (digest != null) {
                        digest.update(buffer, 0, n);
                    }
                    out.write(buffer, 0, n);
                }
            }
            return memory != null ? memory.toByteArray() : null;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean hasAllowedExtension(String name) {
        for (String extension : ALLOWED_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVersion(String value) {
        return value != null && value.matches("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}");
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static String queuedVersion(File pending) {
        try {
            String text = read(new File(pending, "meta.json"));
            String version = text == null ? null : new JSONObject(text).optString("version", null);
            return isVersion(version) ? version : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readVersion(File directory) {
        String version = read(new File(directory, "version.txt"));
        return isVersion(version) ? version : null;
    }

    private static String read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
