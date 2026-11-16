package com.gakiji.aiaccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 GitHub Releases 查询正式版本。只比对三段版本号并把下载地址交给浏览器，
 * 应用内不下载、不安装，也不申请安装权限。
 */
final class Updates {
    static final String OWNER = "gjkbjj";
    static final String REPO = "aicount";
    static final String PAGE = "https://github.com/" + OWNER + "/" + REPO;
    static final String API = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    static final long INTERVAL = 6 * 60 * 60 * 1000L;
    private static final String ASSET = "AIAccount";
    private static final int LIMIT = 1024 * 1024;

    private Updates() {}

    record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static final Pattern PATTERN = Pattern.compile(
                "^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");

        static Version parse(String text) {
            if (text == null) return null;
            Matcher match = PATTERN.matcher(text.strip());
            if (!match.matches()) return null;
            try {
                return new Version(Integer.parseInt(match.group(1)),
                        Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }

        @Override public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) return result;
            result = Integer.compare(minor, other.minor);
            return result != 0 ? result : Integer.compare(patch, other.patch);
        }

        @Override public String toString() { return major + "." + minor + "." + patch; }
    }

    record Release(String version, String notes, String apkUrl, long size) {
        String assetName() { return ASSET + "-" + version + ".apk"; }

        /** 缓存成与接口同构的 JSON，重启后仍走同一套校验。 */
        String cache() throws JSONException {
            return new JSONObject().put("tag_name", "v" + version).put("body", notes)
                    .put("assets", new JSONArray().put(new JSONObject()
                            .put("name", assetName()).put("state", "uploaded")
                            .put("browser_download_url", apkUrl).put("size", size)))
                    .toString();
        }
    }

    static final class CheckFailure extends Exception {
        CheckFailure(String message) { super(message); }
    }

    /** 便于测试注入的请求器；正式实现只允许 https 且不跟随重定向。 */
    interface Source {
        String read(String url, String userAgent) throws IOException, CheckFailure;
    }

    static Source http() { return Updates::download; }

    private static String download(String url, String userAgent) throws IOException, CheckFailure {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", userAgent);
            int code = connection.getResponseCode();
            if (code == 404) throw new CheckFailure("暂未找到已发布的正式版本，请稍后再试。");
            if (code < 200 || code >= 300) throw new CheckFailure("更新检查暂不可用，请稍后再试。");
            byte[] data;
            try (InputStream in = connection.getInputStream()) {
                data = in.readNBytes(LIMIT + 1);
            }
            if (data.length > LIMIT) throw new CheckFailure("更新信息过大，请稍后再试。");
            if (data.length == 0) throw new CheckFailure("更新源返回空响应。");
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    static Release latest(String installed, String userAgent, Source source) throws IOException, CheckFailure {
        return parse(source.read(API, userAgent), installed);
    }

    /** 返回 null 表示已是最新（或发布被标记为草稿、预发布）。 */
    static Release parse(String json, String installed) throws CheckFailure {
        Version current = Version.parse(installed);
        if (current == null) throw new CheckFailure("当前测试版本暂不支持此更新渠道。");
        JSONObject release;
        try {
            release = new JSONObject(json);
        } catch (JSONException invalid) {
            throw new CheckFailure("暂时无法识别更新信息。");
        }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String tag = release.optString("tag_name");
        Version next = Version.parse(tag);
        if (next == null) throw new CheckFailure("更新源版本号格式不正确。");
        if (next.compareTo(current) <= 0) return null;
        String name = ASSET + "-" + next + ".apk";
        String expected = PAGE + "/releases/download/" + tag + "/" + name;
        JSONArray assets = release.optJSONArray("assets");
        JSONObject asset = null;
        for (int index = 0; assets != null && index < assets.length(); index++) {
            JSONObject candidate = assets.optJSONObject(index);
            if (candidate == null || !name.equals(candidate.optString("name"))
                    || !"uploaded".equals(candidate.optString("state"))) continue;
            if (asset != null) {
                asset = null;
                break;
            }
            asset = candidate;
        }
        if (asset == null) throw new CheckFailure("新版本尚未提供可用安装包，请稍后再试。");
        if (!expected.equals(asset.optString("browser_download_url")) || asset.optLong("size") <= 0) {
            throw new CheckFailure("更新安装包地址无效，请稍后再试。");
        }
        return new Release(next.toString(), shorten(release.optString("body"), 8000), expected, asset.optLong("size"));
    }

    static boolean shouldCheck(long last, long now) {
        return last <= 0 || now < last || now - last >= INTERVAL;
    }

    /** 已知必须更新时不会被一次网络失败或回滚覆盖。 */
    static Release required(Release known, Release discovered) {
        if (known == null) return discovered;
        if (discovered == null) return known;
        return Version.parse(known.version()).compareTo(Version.parse(discovered.version())) >= 0 ? known : discovered;
    }

    private static String shorten(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
