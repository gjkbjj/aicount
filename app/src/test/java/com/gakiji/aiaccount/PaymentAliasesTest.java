package com.gakiji.aiaccount;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PaymentAliasesTest {
    @Test public void supportsLegacyAndMultipleAliases() {
        assertEquals(PaymentAliases.EXACT, PaymentAliases.match("零钱", "零钱"));
        assertEquals(PaymentAliases.EXACT, PaymentAliases.match("亲属卡、亲情卡", "亲情卡"));
        assertEquals(PaymentAliases.PARTIAL, PaymentAliases.match("亲属卡、亲情卡", "亲情卡(示例)"));
        // 微信写亲属卡、支付宝写亲情卡，同一个账户别名要能同时覆盖。
        assertEquals(PaymentAliases.PARTIAL, PaymentAliases.match("亲情卡", "亲属卡(明哥)"));
        assertEquals(PaymentAliases.PARTIAL, PaymentAliases.match("亲属卡", "亲情卡(良辉(刘良辉))"));
        assertEquals(PaymentAliases.EXACT, PaymentAliases.match("亲情卡（示例）", "亲情卡(示例)"));
        assertEquals(0, PaymentAliases.match("零钱", "零钱通"));
        assertEquals(0, PaymentAliases.match("网商银行储蓄卡(1234)", "网商银行储蓄卡(5678)"));
        assertEquals(PaymentAliases.EXACT, PaymentAliases.match("网商银行储蓄卡(1234)", "网商银行储蓄卡(1234) 查看扣款顺序"));
        assertEquals(0, PaymentAliases.match("", ""));
        assertEquals("亲属卡、亲情卡", PaymentAliases.save(" 亲属卡，亲情卡,\n亲情卡 "));
    }

    @Test public void matchesBankNicknameWithoutCardSuffix() {
        // 账单写「网商银行储蓄卡(6627)」，账户别名只写「网商」也要能对上。
        assertEquals(PaymentAliases.CORE, PaymentAliases.match("支付宝、网商", "网商银行储蓄卡(6627)"));
        assertEquals(PaymentAliases.CORE, PaymentAliases.match("中国银行", "中国银行储蓄卡(1234)"));
        assertEquals(PaymentAliases.CORE, PaymentAliases.match("余额", "账户余额"));
        assertEquals(PaymentAliases.PARTIAL, PaymentAliases.match("工商", "中国工商银行储蓄卡(1234)"));
        // 前缀不同、后缀不同即不是同一家银行，不参与匹配。
        assertEquals(0, PaymentAliases.match("中国银行", "中国工商银行储蓄卡(1234)"));
        assertEquals(0, PaymentAliases.match("网商", "招商银行储蓄卡(6627)"));
        assertEquals(0, PaymentAliases.match("余额", "余额宝"));
    }
}
