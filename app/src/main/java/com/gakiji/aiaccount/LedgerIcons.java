package com.gakiji.aiaccount;

final class LedgerIcons {
    private static final String[][] BANK_NAMES = {
            {"中国银行", "中行"}, {"工商银行", "工行"}, {"建设银行", "建行"},
            {"农业银行", "农行"}, {"交通银行", "交行"}, {"招商银行", "招行"},
            {"邮政储蓄", "邮储"}, {"平安银行", "平安"}, {"中信银行", "中信"},
            {"浦发银行", "上海浦东发展银行", "浦发"}, {"网商银行"}
    };
    private static final int[] BANK_ICONS = {R.drawable.brand_boc, R.drawable.brand_icbc, R.drawable.brand_ccb,
            R.drawable.brand_abc, R.drawable.brand_bocom, R.drawable.brand_cmb, R.drawable.brand_psbc,
            R.drawable.brand_pingan, R.drawable.brand_citic, R.drawable.brand_spd, R.drawable.brand_mybank};

    static int source(String source) {
        return switch (source) {
            case Bill.WECHAT -> R.drawable.brand_wechat;
            case Bill.ALIPAY -> R.drawable.brand_alipay;
            default -> 0;
        };
    }

    static int payment(String value, String source) {
        value = value.strip();
        if (value.contains("亲属卡") || value.contains("亲情卡")) return R.drawable.ui_family;
        if (value.contains("现金")) return R.drawable.ui_cash;
        for (int i = 0; i < BANK_NAMES.length; i++) {
            for (String name : BANK_NAMES[i]) if (value.contains(name)) return BANK_ICONS[i];
        }
        if (value.contains("银行")) return R.drawable.ui_bank;
        if (value.contains("信用卡") || value.contains("储蓄卡") || value.contains("借记卡")
                || value.contains("银行卡")) return R.drawable.ui_bankcard;
        if (value.contains("微信") || value.equals("零钱") || value.equals("零钱通")) return R.drawable.brand_wechat;
        if (value.contains("支付宝") || value.contains("余额宝") || value.contains("花呗")) return R.drawable.brand_alipay;
        if (value.equals("余额") && source.equals(Bill.ALIPAY)) return R.drawable.brand_alipay;
        return 0;
    }

    static int category(String id) {
        return switch (id) {
            case "food" -> R.drawable.ui_food;
            case "transport" -> R.drawable.ui_bus;
            case "shopping" -> R.drawable.ui_shopping;
            case "housing_utilities" -> R.drawable.ui_home;
            case "telecom_subscription" -> R.drawable.ui_phone;
            case "health" -> R.drawable.ui_health;
            case "education" -> R.drawable.ui_school;
            case "leisure_travel" -> R.drawable.ui_travel;
            case "gifts", "gift_income" -> R.drawable.ui_gift;
            case "earned_income" -> R.drawable.ui_work;
            case "investment_income" -> R.drawable.ui_investment;
            case "other_income" -> R.drawable.ui_cash;
            case Categories.RECONCILIATION, Categories.BALANCE_ONLY -> R.drawable.ui_reconcile;
            default -> R.drawable.ui_receipt;
        };
    }
}
