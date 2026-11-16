package com.gakiji.aiaccount;

import java.util.LinkedHashMap;
import java.util.Map;

final class Categories {
    static final String RECONCILIATION = "reconciliation";
    static final String BALANCE_ONLY = "balance_only";

    static boolean adjustment(String category) {
        return RECONCILIATION.equals(category) || BALANCE_ONLY.equals(category);
    }

    static final String UNKNOWN = "uncategorized";
    static String fallback(int direction) {
        return direction < 0 ? "other_expense" : "other_income";
    }

    static String visibleId(String category, int direction) {
        return adjustment(category) || forDirection(direction).containsKey(category) ? category : fallback(direction);
    }

    static String label(String category, int direction) {
        if (adjustment(category)) return "平账";
        var labels = forDirection(direction);
        return labels.getOrDefault(category, labels.get(fallback(direction)));
    }

    static Map<String, String> forDirection(int direction) {
        Map<String, String> result = new LinkedHashMap<>();
        if (direction < 0) {
            result.put("food", "餐饮");
            result.put("transport", "交通");
            result.put("shopping", "购物日用");
            result.put("housing_utilities", "居住缴费");
            result.put("telecom_subscription", "通讯订阅");
            result.put("health", "医疗健康");
            result.put("education", "教育学习");
            result.put("leisure_travel", "娱乐旅行");
            result.put("gifts", "人情礼金");
            result.put(fallback(direction), "其他支出");
        } else {
            result.put("earned_income", "工资劳务");
            result.put("investment_income", "利息与投资收益");
            result.put("gift_income", "人情礼金");
            result.put(fallback(direction), "其他收入");
        }
        return result;
    }
}
