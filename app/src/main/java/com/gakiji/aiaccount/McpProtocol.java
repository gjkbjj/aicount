package com.gakiji.aiaccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;

final class McpProtocol {
    private static final String TOOL = "ledger_statistics";
    private static final String DEFAULT_VERSION = "2025-11-25";
    static final Set<String> VERSIONS = Set.of("2025-03-26", "2025-06-18", DEFAULT_VERSION);
    static final Set<String> GROUPS = Set.of("none", "day", "category", "source");

    record Query(LocalDate from, LocalDate to, String group) {}
    interface Statistics { JSONObject read(Query query) throws Exception; }
    record Reply(int status, JSONObject body) {}

    static Reply handle(String body, Statistics statistics) throws JSONException {
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (JSONException invalid) {
            return new Reply(400, error(JSONObject.NULL, -32700, "Parse error"));
        }
        Object id = request.opt("id");
        String method = request.optString("method");
        if (!"2.0".equals(request.optString("jsonrpc")) || method.isEmpty()
                || (id != null && !(id instanceof Number || id instanceof String))) {
            return new Reply(400, error(JSONObject.NULL, -32600, "Invalid Request"));
        }
        if (id == null) return new Reply(202, null);
        JSONObject params = request.optJSONObject("params");
        JSONObject result;
        switch (method) {
            case "initialize" -> {
                String requested = params == null ? "" : params.optString("protocolVersion");
                result = new JSONObject()
                        .put("protocolVersion", VERSIONS.contains(requested) ? requested : DEFAULT_VERSION)
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "aiaccount").put("version", "0.2.10"));
            }
            case "ping" -> result = new JSONObject();
            case "tools/list" -> result = new JSONObject().put("tools", new JSONArray().put(tool()));
            case "tools/call" -> {
                if (params == null || !TOOL.equals(params.optString("name"))) {
                    return new Reply(200, error(id, -32602, "Unknown tool"));
                }
                try {
                    Query query = query(params.optJSONObject("arguments"));
                    result = new JSONObject()
                            .put("content", content(statistics.read(query).toString()));
                } catch (IllegalArgumentException invalid) {
                    result = new JSONObject().put("isError", true).put("content", content(invalid.getMessage()));
                } catch (Exception unavailable) {
                    result = new JSONObject().put("isError", true).put("content", content("暂时无法读取账本"));
                }
            }
            default -> { return new Reply(200, error(id, -32601, "Method not found")); }
        }
        return new Reply(200, new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result));
    }

    private static Query query(JSONObject args) {
        if (args == null) {
            throw new IllegalArgumentException("请提供 from_date 和 to_date");
        }
        var keys = args.keys();
        while (keys.hasNext()) {
            if (!Set.of("from_date", "to_date", "group_by").contains(keys.next())) {
                throw new IllegalArgumentException("统计请求含未知字段");
            }
        }
        try {
            String fromText = args.optString("from_date");
            String toText = args.optString("to_date");
            if (!fromText.matches("\\d{4}-\\d{2}-\\d{2}") || !toText.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException("日期格式为 YYYY-MM-DD");
            }
            LocalDate from = LocalDate.parse(fromText);
            LocalDate to = LocalDate.parse(toText);
            String group = args.optString("group_by", "none");
            long days = ChronoUnit.DAYS.between(from, to);
            if (!GROUPS.contains(group) || days < 0 || days > 365) {
                throw new IllegalArgumentException("日期区间最多 366 天，分组可选 none、day、category、source");
            }
            return new Query(from, to, group);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("日期格式为 YYYY-MM-DD");
        }
    }

    private static JSONObject tool() throws JSONException {
        var date = new JSONObject().put("type", "string").put("format", "date");
        var properties = new JSONObject().put("from_date", date).put("to_date", date)
                .put("group_by", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("none").put("day").put("category").put("source")));
        return new JSONObject().put("name", TOOL)
                .put("description", "按日期区间汇总已入账收支，可按日期、分类、来源应用分组。金额单位为人民币分。"
                        + "退款按退款发生日冲减支出。区间包含首尾两日，最多366天。")
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", new JSONArray().put("from_date").put("to_date"))
                        .put("additionalProperties", false))
                .put("annotations", new JSONObject().put("readOnlyHint", true).put("openWorldHint", false));
    }

    private static JSONArray content(String text) throws JSONException {
        return new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
    }

    private static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message));
    }
}
