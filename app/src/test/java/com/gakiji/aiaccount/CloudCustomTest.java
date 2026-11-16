package com.gakiji.aiaccount;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CloudCustomTest {
    @Test public void normalizesCustomEndpointToChatCompletions() throws Exception {
        assertEquals("https://api.example.com/v1/chat/completions",
                Cloud.endpoint("https://api.example.com/v1/"));
        assertEquals("https://api.example.com/v1/chat/completions",
                Cloud.endpoint("  https://api.example.com/v1/chat/completions  "));
        assertThrows(IOException.class, () -> Cloud.endpoint("http://api.example.com/v1"));
        assertThrows(IOException.class, () -> Cloud.endpoint(""));
        assertThrows(IOException.class, () -> Cloud.endpoint("https://"));
    }

    @Test public void requiresModelName() throws Exception {
        assertEquals("qwen3.6-flash", Cloud.model(" qwen3.6-flash "));
        assertThrows(IOException.class, () -> Cloud.model("   "));
    }

    @Test public void readsJsonWrappedInFencesOrProse() throws Exception {
        assertEquals("{\"category_id\":\"food\"}", Cloud.jsonObject("```json\n{\"category_id\":\"food\"}\n```"));
        assertEquals("{\"a\":1}", Cloud.jsonObject("结果如下：{\"a\":1} 请查收"));
        assertThrows(IOException.class, () -> Cloud.jsonObject("没有 JSON"));
    }
}
