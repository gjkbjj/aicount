package com.gakiji.aiaccount;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UpdatesTest {
    private static final String INSTALLED = "0.2.16";
    private static final String APK = "AIAccount-0.2.17.apk";
    private static final String URL = "https://github.com/gjkbjj/aicount/releases/download/v0.2.17/" + APK;

    @Test public void comparesOnlyThreePartVersions() {
        assertTrue(Updates.Version.parse("0.2.17").compareTo(Updates.Version.parse("0.2.16")) > 0);
        assertEquals(0, Updates.Version.parse("v0.2.16").compareTo(Updates.Version.parse("0.2.16")));
        assertTrue(Updates.Version.parse("1.0.0").compareTo(Updates.Version.parse("0.99.99")) > 0);
        for (String refused : new String[]{"0.2", "0.2.16-beta", "00.2.16", "", "0.2.0.1", "99999999999.0.0"}) {
            assertNull(refused, Updates.Version.parse(refused));
        }
    }

    @Test public void ignoresDraftPrereleaseAndOlderTags() throws Exception {
        assertNull(Updates.parse(json("v0.2.17", APK, URL, true, false), INSTALLED));
        assertNull(Updates.parse(json("v0.2.17", APK, URL, false, true), INSTALLED));
        assertNull(Updates.parse(json("v0.2.16", "AIAccount-0.2.16.apk",
                "https://github.com/gjkbjj/aicount/releases/download/v0.2.16/AIAccount-0.2.16.apk",
                false, false), INSTALLED));
        assertEquals("更新源版本号格式不正确。",
                assertThrows(Updates.CheckFailure.class,
                        () -> Updates.parse(json("v0.2.17-rc1", APK, URL, false, false), INSTALLED)).getMessage());
    }

    @Test public void acceptsOnlyTheExpectedAsset() throws Exception {
        Updates.Release release = Updates.parse(json("v0.2.17", APK, URL, false, false), INSTALLED);
        assertEquals("0.2.17", release.version());
        assertEquals(URL, release.apkUrl());
        assertEquals(1024, release.size());
        assertThrows(Updates.CheckFailure.class,
                () -> Updates.parse(json("v0.2.17", "aiaccount-0.2.17.apk", URL, false, false), INSTALLED));
        assertThrows(Updates.CheckFailure.class, () -> Updates.parse(json("v0.2.17", APK,
                "https://example.com/AIAccount-0.2.17.apk", false, false), INSTALLED));
        assertThrows(Updates.CheckFailure.class, () -> Updates.parse(json("v0.2.17", APK, URL, 0, false, false),
                INSTALLED));
        assertThrows(Updates.CheckFailure.class, () -> Updates.parse(duplicateAsset(), INSTALLED));
        String plainTag = "https://github.com/gjkbjj/aicount/releases/download/0.2.17/" + APK;
        assertEquals("0.2.17", Updates.parse(json("0.2.17", APK, plainTag, false, false), INSTALLED).version());
    }

    @Test public void restoresCachedReleaseThroughTheSameValidation() throws Exception {
        Updates.Release release = Updates.parse(json("v0.2.17", APK, URL, false, false), INSTALLED);
        String cached = release.cache();
        assertEquals(release, Updates.parse(cached, INSTALLED));
        assertNull(Updates.parse(cached, "0.2.17"));
    }

    @Test public void throttlesChecksButNotAfterClockRollback() {
        long now = 1_760_000_000_000L;
        assertTrue(Updates.shouldCheck(0, now));
        assertFalse(Updates.shouldCheck(now - 60_000, now));
        assertTrue(Updates.shouldCheck(now - Updates.INTERVAL, now));
        assertTrue(Updates.shouldCheck(now + 60_000, now));
    }

    @Test public void keepsTheHigherKnownRelease() throws Exception {
        Updates.Release old = Updates.parse(json("v0.2.17", APK, URL, false, false), INSTALLED);
        assertTrue(old == Updates.required(old, null));
        assertTrue(old == Updates.required(old, Updates.parse(json("v0.2.16", "AIAccount-0.2.16.apk",
                "https://github.com/gjkbjj/aicount/releases/download/v0.2.16/AIAccount-0.2.16.apk",
                false, false), INSTALLED)));
        assertNull(Updates.required(null, null));
    }

    @Test public void asksTheReleaseApiWithoutCredentials() throws Exception {
        var seen = new AtomicReference<String>();
        var agent = new AtomicReference<String>();
        String body = json("v0.2.17", APK, URL, false, false);
        Updates.Release release = Updates.latest(INSTALLED, "AiAccount/" + INSTALLED, (url, userAgent) -> {
            seen.set(url);
            agent.set(userAgent);
            return body;
        });
        assertEquals(URL, release.apkUrl());
        assertEquals(Updates.API, seen.get());
        assertTrue(Updates.API.startsWith("https://api.github.com/repos/"));
        assertFalse(Updates.API.contains("token"));
        assertEquals("AiAccount/" + INSTALLED, agent.get());
    }

    private static String json(String tag, String name, String url, boolean draft, boolean prerelease) throws Exception {
        return release(tag, new JSONArray().put(asset(name, url, 1024)), draft, prerelease);
    }

    private static String json(String tag, String name, String url, long size, boolean draft, boolean prerelease) throws Exception {
        return release(tag, new JSONArray().put(asset(name, url, size)), draft, prerelease);
    }

    private static String duplicateAsset() throws Exception {
        return release("v0.2.17",
                new JSONArray().put(asset(APK, URL, 1024)).put(asset(APK, URL, 1024)), false, false);
    }

    private static String release(String tag, JSONArray assets, boolean draft, boolean prerelease) throws Exception {
        return new JSONObject().put("tag_name", tag).put("body", "修复若干问题").put("draft", draft)
                .put("prerelease", prerelease).put("assets", assets).toString();
    }

    private static JSONObject asset(String name, String url, long size) throws Exception {
        return new JSONObject().put("name", name).put("state", "uploaded")
                .put("browser_download_url", url).put("size", size);
    }
}
