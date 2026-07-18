package com.formationtool.desktop.update;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

public final class UpdateChecker {

    private static final String MANIFEST_URL =
            "https://pub-02ac16656b17473f97b1b838e1bc546d.r2.dev/dancefactory/updates/latest.json";

    private UpdateChecker() {}

    public static final class UpdateInfo {
        public final String latestVersion;
        public final String downloadUrl;
        public final String fileName;
        public final String sha256;
        public final long size;
        public final String notes;
        public final boolean mandatory;

        private UpdateInfo(String latestVersion, String downloadUrl, String fileName,
                           String sha256, long size, String notes, boolean mandatory) {
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
            this.mandatory = mandatory;
        }
    }

    public static String getManifestUrl() {
        return MANIFEST_URL;
    }

    public static UpdateInfo check(String currentVersion) throws Exception {
        return check(currentVersion, "windows");
    }

    public static UpdateInfo check(String currentVersion, String platform) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(MANIFEST_URL).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("User-Agent", "DanceFactory-Updater/1.1");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            JsonObject root = UpdateManifestVerifier.verifyAndParse(sb.toString());
            String remoteVersion = root.get("version").getAsString();
            boolean mandatory = root.has("mandatory") && root.get("mandatory").getAsBoolean();

            JsonObject platformObj = root.getAsJsonObject("artifacts").getAsJsonObject(platform);
            String dlUrl = platformObj.get("downloadUrl").getAsString();
            String fileName = platformObj.get("fileName").getAsString();
            String sha256 = platformObj.get("sha256").getAsString();
            long size = platformObj.get("size").getAsLong();

            StringBuilder notesStr = new StringBuilder();
            if (root.has("notes")) {
                for (var e : root.getAsJsonArray("notes")) {
                    notesStr.append("• ").append(e.getAsString()).append("\n");
                }
            }

            if (compareVersions(remoteVersion, currentVersion) > 0) {
                return new UpdateInfo(remoteVersion, dlUrl, fileName,
                        sha256, size, notesStr.toString().trim(), mandatory);
            }
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }
}
