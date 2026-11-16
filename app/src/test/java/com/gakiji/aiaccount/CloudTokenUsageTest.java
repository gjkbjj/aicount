package com.gakiji.aiaccount;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CloudTokenUsageTest {
    @Test public void readsProviderUsageWithoutCountingCacheBreakdownTwice() throws Exception {
        assertEquals(new Cloud.TokenUsage(120, 30, 150), Cloud.TokenUsage.from(new JSONObject("""
                {"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,
                "prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":15}}}
                """)));
        assertEquals(new Cloud.TokenUsage(9, 4, 13), Cloud.TokenUsage.from(new JSONObject("""
                {"usage":{"prompt_tokens":9,"completion_tokens":4}}
                """)));
        assertEquals(new Cloud.TokenUsage(0, 0, 0), Cloud.TokenUsage.from(new JSONObject("{}")));
    }
}
