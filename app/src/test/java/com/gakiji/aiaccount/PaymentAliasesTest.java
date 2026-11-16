package com.gakiji.aiaccount;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PaymentAliasesTest {
    @Test public void supportsLegacyAndMultipleAliases() {
        assertEquals(2, PaymentAliases.match("零钱", "零钱"));
        assertEquals(2, PaymentAliases.match("亲属卡、亲情卡", "亲情卡"));
        assertEquals(1, PaymentAliases.match("亲属卡、亲情卡", "亲情卡(示例)"));
        assertEquals(2, PaymentAliases.match("亲情卡（示例）", "亲情卡(示例)"));
        assertEquals(0, PaymentAliases.match("零钱", "零钱通"));
        assertEquals(0, PaymentAliases.match("网商银行储蓄卡(1234)", "网商银行储蓄卡(5678)"));
        assertEquals(2, PaymentAliases.match("网商银行储蓄卡(1234)", "网商银行储蓄卡(1234) 查看扣款顺序"));
        assertEquals(0, PaymentAliases.match("", ""));
        assertEquals("亲属卡、亲情卡", PaymentAliases.save(" 亲属卡，亲情卡,\n亲情卡 "));
    }
}
