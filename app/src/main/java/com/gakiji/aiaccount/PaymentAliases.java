package com.gakiji.aiaccount;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 账户支付方式别名。账单里的付款方式往往带银行、卡种和尾号，用户只手写银行简称，
 * 因此按「完全同名 &gt; 去掉通用词后同名 &gt; 银行卡名后缀包含 &gt; 亲属卡降级」打分。
 */
final class PaymentAliases {
    /** 归一后完全相同。 */
    static final int EXACT = 3;
    /** 去掉「银行、储蓄卡、信用卡、尾号」等通用词后同名，例如「网商」与「网商银行储蓄卡(6627)」。 */
    static final int CORE = 2;
    /** 银行卡名后缀包含，例如「工商」与「中国工商银行储蓄卡(1234)」。 */
    static final int PARTIAL = 1;

    private static final String FAMILY = "亲属卡";
    private static final String SEPARATORS = "[、,，;；\\r\\n]+";
    private static final Pattern TAIL = Pattern.compile(
            "(?:(?:尾号|卡号|账号)\\s*(\\d{4})|[(（\\[]\\s*(\\d{4})\\s*[)）\\]])\\s*$");
    private static final Pattern DECORATION = Pattern.compile(
            "储蓄卡|信用卡|借记卡|贷记卡|银行卡|开户行|账户|帐户|网银|银行|尾号|卡号|卡");
    private static final Pattern CARD = Pattern.compile(
            "银行|储蓄卡|信用卡|借记卡|贷记卡|银行卡|尾号|卡号|(?i)card|(?i)bank");

    static String save(String input) {
        return String.join("、", java.util.Arrays.stream(input.split(SEPARATORS))
                .map(String::strip).filter(alias -> !alias.isEmpty()).distinct().toList());
    }

    static int match(String aliases, String payment) {
        String paid = normalize(payment);
        if (paid.isEmpty()) return 0;
        String paidCore = core(paid);
        String paidTail = tail(paid);
        String paidFamily = family(paid);
        boolean card = CARD.matcher(paid).find();
        int score = 0;
        for (String raw : aliases.split(SEPARATORS)) {
            String alias = normalize(raw);
            if (alias.isEmpty()) continue;
            if (alias.equals(paid)) return EXACT;
            String aliasCore = core(alias);
            if (aliasCore.isEmpty()) continue;
            String aliasTail = tail(alias);
            // 别名写明尾号而账单是另一张卡时不再当作同一个账户。
            if (!aliasTail.isEmpty() && !paidTail.isEmpty() && !aliasTail.equals(paidTail)) continue;
            if (aliasCore.equals(paidCore)) {
                score = Math.max(score, CORE);
            } else if (!paidFamily.isEmpty() && paidFamily.equals(family(alias))) {
                // 微信写亲属卡、支付宝写亲情卡，账单还会附上持卡人，别名只写卡名即可。
                score = Math.max(score, PARTIAL);
            } else if (card && suffix(aliasCore, paidCore)) {
                // 「工商」对应「中国工商银行储蓄卡」，前缀不同但后缀一致。
                score = Math.max(score, PARTIAL);
            }
        }
        return score;
    }

    /** 亲属卡与亲情卡是同一种卡，账单附带的持卡人不影响归属。 */
    private static String family(String text) {
        return text.startsWith("亲属卡") || text.startsWith("亲情卡") ? FAMILY : "";
    }

    private static boolean suffix(String aliasCore, String paidCore) {
        return aliasCore.length() >= 2 && paidCore.length() > aliasCore.length()
                && paidCore.endsWith(aliasCore);
    }

    private static String tail(String text) {
        Matcher matcher = TAIL.matcher(text);
        if (!matcher.find()) return "";
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static String core(String text) {
        String value = TAIL.matcher(text).replaceAll("");
        // 少数界面把尾号写成「储蓄卡6627」，只在银行卡类付款方式上去掉结尾四位。
        if (value.length() == text.length() && CARD.matcher(text).find()) {
            value = text.replaceFirst("\\d{4}$", "");
        }
        return DECORATION.matcher(value).replaceAll("").strip();
    }

    private static String normalize(String text) {
        return text.strip().replace('（', '(').replace('）', ')')
                .replaceFirst("\\s*\\(", "(")
                .replaceFirst("\\s*查看扣款顺序\\s*[>›＞]?\\s*$", "");
    }
}
