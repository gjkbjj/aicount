package com.gakiji.aiaccount;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BillTest {
    private java.util.Locale systemLanguage;

    @Before public void useEnglishSystemLanguage() {
        systemLanguage = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
    }

    @After public void restoreSystemLanguage() {
        java.util.Locale.setDefault(systemLanguage);
    }

    @Test public void sourceAndTenderAreIndependent() {
        Bill alipay = Bill.parse(Bill.ALIPAY, List.of("支付成功", "实付金额", "6.72",
                "交易号：2026111601234567", "支付方式：中国银行信用卡（尾号 1234）",
                "商家：咖啡店", "优惠：¥2.00"));
        assertNotNull(alipay);
        assertEquals("支付宝", Bill.label(alipay.source()));
        assertEquals("中国银行信用卡（尾号 1234）", alipay.payment());
        assertEquals("1234", alipay.lastFour());
        assertEquals(672, alipay.cents());
        assertEquals(-1, alipay.direction());
        Bill wechat = Bill.parse(Bill.WECHAT, List.of("收款成功", "收款金额：5.39", "到账账户：零钱"));
        assertNotNull(wechat);
        assertEquals("微信", Bill.label(wechat.source()));
        assertEquals("零钱", wechat.payment());
        assertEquals(539, wechat.cents());
        assertEquals(1, wechat.direction());
        assertTrue(wechat.order().isEmpty());
    }

    @Test public void sameOrderDedupesAcrossViewsButNotAcrossPlatforms() {
        List<String> detail = List.of("账单详情", "当前状态：交易成功", "收支类型：支出", "实付金额：10.00",
                "交易单号：2026111601234567", "付款方式：零钱");
        Bill first = Bill.parse(Bill.WECHAT, detail);
        Bill success = Bill.parse(Bill.WECHAT, List.of("支付成功", "实付金额：10", "交易单号：2026111601234567"));
        Bill other = Bill.parse(Bill.ALIPAY, detail);
        assertFalse(first.successPage());
        assertTrue(success.successPage());
        assertEquals(first.fingerprint(), success.fingerprint());
        assertNotEquals(first.fingerprint(), other.fingerprint());
    }

    @Test public void rejectsNonBillAmbiguousAndFailedPages() {
        assertNull(Bill.parse("other.package", List.of("支付成功", "金额：6.72")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("账单", "商家：咖啡店", "金额：6.72")));
        assertNull(Bill.parse(Bill.ALIPAY, List.of("支付成功", "支付密码", "金额：6.72")));
        assertNull(Bill.parse(Bill.ALIPAY, List.of("支付成功", "支付失败", "金额：6.72")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("支付成功", "已退款", "金额：6.72")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("支付成功", "USD", "金额：6.72")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("支付成功", "实付金额：6.72", "实收金额：5.39")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("支付成功", "¥6.72", "¥5.39")));
        assertEquals(123450, Bill.amountCents("￥1,234.50"));
        assertEquals(0, Bill.amountCents("1.001"));
    }

    @Test public void postsOnlyIdentifiableRecentOrOptedInBills() {
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var now = java.time.Instant.parse("2026-11-15T16:00:00Z");
        Bill bill = Bill.parse(Bill.ALIPAY, List.of("当前状态：交易成功", "实付金额：6.72",
                "支付时间：2026/11/15 23:59:00", "交易号：2026111601234567"));
        assertTrue(bill.shouldPost(false, now, zone));
        assertFalse(bill.shouldPost(false, now.plusSeconds(360), zone));
        assertTrue(bill.shouldPost(true, now.plusSeconds(360), zone));
        Bill incomplete = Bill.parse(Bill.ALIPAY, List.of("支付成功", "实付金额：6.72"));
        assertFalse(incomplete.shouldPost(true, now, zone));
    }

    @Test public void wechatPaymentResultWithoutFundingAccountStaysInDrafts() {
        var now = java.time.Instant.parse("2026-11-15T16:00:00Z");
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        Bill draft = Bill.parse(Bill.WECHAT, List.of("微信支付成功", "示例商家", "12.34", "完成"));
        assertNotNull(draft);
        assertEquals(1234, draft.cents());
        assertEquals(-1, draft.direction());
        assertEquals("", draft.payment());
        assertEquals("示例商家", draft.merchant());
        assertFalse(draft.shouldPost(true, now, zone));
        Bill numbered = Bill.parse(Bill.WECHAT, List.of("支付成功", "¥12.34",
                "交易单号：2026111601234567", "支付方式：微信支付"));
        assertNotNull(numbered);
        assertEquals("", numbered.payment());
        assertFalse(numbered.shouldPost(true, now, zone));
        assertNull(Bill.parse(Bill.WECHAT, List.of("微信")));
        assertNull(Bill.parse(Bill.WECHAT, List.of("支付成功", "¥12.34", "45.67")));
    }

    @Test public void recognizesWechatRefundAndOriginalAsSeparateEntries() {
        Bill refund = Bill.parse(Bill.WECHAT, List.of("示例烧烤 -退款", "+100.00", "退款状态", "已退款",
                "退款时间", "2026年11月15日 13:40:50", "退款方式", "零钱",
                "退款单号", "REFUND-TEST-0001", "交易单号", "PAYMENT-TEST-0001", "原订单", "查看原订单"));
        Bill original = Bill.parse(Bill.WECHAT, List.of("示例烧烤", "−100.00", "当前状态", "已退款",
                "支付时间", "2026年11月15日 11:44:12", "商品", "收银测试", "支付方式", "零钱",
                "交易单号", "PAYMENT-TEST-0001", "商户单号", "可在支持的商户扫码退款"));
        assertNotNull(refund);
        assertNotNull(original);
        assertTrue(refund.refund());
        assertFalse(original.refund());
        assertEquals(10000, refund.cents());
        assertEquals(-1, original.direction());
        assertEquals("零钱", refund.payment());
        assertEquals(original.fingerprint(), refund.originalFingerprint());
        assertNotEquals(original.fingerprint(), refund.fingerprint());
        assertNull(Bill.parse(Bill.WECHAT, List.of("示例烧烤 -退款", "+100.00", "退款状态", "退款中",
                "退款单号", "REFUND-TEST-0001", "交易单号", "PAYMENT-TEST-0001")));
    }

    @Test public void recognizesWechatPurchaseAndRedPacketDetails() {
        Bill purchase = Bill.parse(Bill.WECHAT, List.of("综合商城", "−17.50", "当前状态", "支付成功",
                "支付时间", "2026年11月13日 23:48:37", "商品", "商户单号", "MERCHANT-TEST-0001",
                "支付方式", "亲属卡(示例)", "交易单号", "PAYMENT-TEST-0002"));
        Bill gift = Bill.parse(Bill.WECHAT, List.of("微信红包-来自示例群", "+0.40", "当前状态", "已存入零钱",
                "红包详情", "查看", "收款时间", "2026年11月15日 19:53:56", "交易单号", "PAYMENT-TEST-0003"));
        assertNotNull(purchase);
        assertNotNull(gift);
        assertEquals("综合商城", purchase.merchant());
        assertEquals(1750, purchase.cents());
        assertEquals(-1, purchase.direction());
        assertFalse(purchase.successPage());
        assertEquals("亲属卡(示例)", purchase.payment());
        assertEquals("零钱", gift.payment());
        assertEquals(40, gift.cents());
        assertEquals(1, gift.direction());
        assertFalse(gift.refund());
        assertTrue(gift.shouldPost(false, java.time.Instant.parse("2026-11-15T11:54:00Z"),
                java.time.ZoneId.of("Asia/Shanghai")));
    }

    @Test public void alipayDetailRequiresExplicitPlusForIncome() {
        List<String> fields = List.of("账单详情", "示例出行", "1.00", "交易成功", "支付时间",
                "2026-11-15 20:55:24", "付款方式", "亲情卡(示例)", "商品说明", "示例观光车",
                "收单机构", "示例机构", "更多");
        Bill purchase = Bill.parse(Bill.ALIPAY, fields);
        assertNotNull(purchase);
        assertEquals(100, purchase.cents());
        assertEquals(-1, purchase.direction());
        assertEquals("亲情卡(示例)", purchase.payment());
        assertTrue(purchase.order().isEmpty());
        var incomeFields = new java.util.ArrayList<>(fields);
        incomeFields.set(2, "+1.00");
        assertEquals(1, Bill.parse(Bill.ALIPAY, incomeFields).direction());
        assertNull(Bill.parse(Bill.ALIPAY, List.of("账单详情", "1.00", "交易成功")));
    }

    @Test public void recognizesWechatTransferAndUsesReceiptTime() {
        Bill transfer = Bill.parse(Bill.WECHAT, List.of("转账-来自示例用户", "+10.00",
                "当前状态", "已存入零钱", "转账说明", "微信转账", "转账时间", "2026年11月15日 12:19:24",
                "收款时间", "2026年11月15日 12:20:14", "转账单号", "123456789000000000000000000001"));
        assertNotNull(transfer);
        assertEquals("微信转账收款", transfer.merchant());
        assertEquals(1, transfer.direction());
        assertEquals(1000, transfer.cents());
        assertEquals("零钱", transfer.payment());
        assertEquals("2026年11月15日 12:20:14", transfer.tradeTime());
        assertNull(Bill.parse(Bill.WECHAT, List.of("转账-来自示例用户", "+10.00", "当前状态", "待收款",
                "收款时间", "2026年11月15日 12:20:14", "转账单号", "123456789000000000000000000001")));
    }

    @Test public void groupsWrappedOrderNumberIntoOneIdentity() {
        String first = "123456789000000000000000000002";
        String second = "123456789";
        var fields = new java.util.ArrayList<>(List.of("群收款-来自示例群", "+22.89",
                "当前状态", "已存入零钱", "收款详情", "查看", "收款时间", "2026年11月8日 20:42:42", "交易单号"));
        fields.add(first);
        fields.add(second);
        Bill wrapped = Bill.parse(Bill.WECHAT, fields);
        assertNotNull(wrapped);
        assertEquals("微信群收款", wrapped.merchant());
        assertEquals(2289, wrapped.cents());
        assertEquals(1, wrapped.direction());
        assertEquals("零钱", wrapped.payment());
        assertEquals(first + second, wrapped.order());
        fields.remove(fields.size() - 1);
        fields.set(fields.size() - 1, first + second);
        assertEquals(wrapped.fingerprint(), Bill.parse(Bill.WECHAT, fields).fingerprint());
        fields.set(fields.size() - 1, first + "\n" + second);
        assertEquals(wrapped.fingerprint(), Bill.parse(Bill.WECHAT, fields).fingerprint());
    }

    @Test public void alipayReadsMergedAndReorderedAccessibilityNodes() {
        Bill merged = Bill.parse(Bill.ALIPAY, List.of(
                "账单详情 示例出行 1.00 交易成功 支付时间：2026-11-15 20:55:24 付款方式：亲情卡(示例) 商品说明：观光车"));
        assertNotNull(merged);
        assertEquals(100, merged.cents());
        assertEquals(-1, merged.direction());
        assertEquals("示例出行", merged.merchant());
        assertEquals("亲情卡(示例)", merged.payment());
        assertTrue(merged.order().isEmpty());

        Bill reordered = Bill.parse(Bill.ALIPAY, List.of("支付时间：2026-11-15 20:47:35",
                "付款方式：亲情卡(示例)", "交易成功", "账单详情", "示例商户", "46.41"));
        assertNotNull(reordered);
        assertEquals(4641, reordered.cents());
        assertEquals(-1, reordered.direction());
        assertEquals("示例商户", reordered.merchant());
        assertNull(Bill.parse(Bill.ALIPAY, List.of("支付时间：2026-11-15 20:47:35", "交易成功",
                "账单详情", "示例商户", "46.41", "20.00")));
        assertNull(Bill.parse(Bill.ALIPAY, List.of("账单详情", "付款方式：亲情卡", "46.41", "支付失败")));
    }

    @Test public void alipayWebViewExposesWholeYuanAndNativeTitleLast() {
        List<String> nodes = List.of("示例出行", "1元", "交易成功", "支付时间",
                "2026-11-15 20:55:24", "2026-11-15 20:55:24", "付款方式",
                "亲情卡(示例)", "商品说明", "观光车", "支付奖励", "立即领取10积分",
                "收款方全称", "示例公司", "更多", "账单管理", "账单分类", "返回", "账单详情");
        Bill bill = Bill.parse(Bill.ALIPAY, nodes);
        assertNotNull(bill);
        assertEquals(100, bill.cents());
        assertEquals(-1, bill.direction());
        assertEquals("示例出行", bill.merchant());
        assertEquals("亲情卡(示例)", bill.payment());
        assertTrue(bill.order().isEmpty());

        var reordered = new java.util.ArrayList<>(nodes);
        reordered.remove("1元");
        reordered.add("1元");
        assertEquals(bill.cents(), Bill.parse(Bill.ALIPAY, reordered).cents());
        var bareNumber = new java.util.ArrayList<>(nodes);
        bareNumber.set(1, "1");
        assertNull(Bill.parse(Bill.ALIPAY, bareNumber));
    }

    @Test public void alipayUsesHeadlineAmountBeforeOrderAndDiscountRows() {
        Bill discounted = Bill.parse(Bill.ALIPAY, List.of("示例出行", "0.79元", "交易成功",
                "订单金额", "1.00", "1.00", "碰一下立减", "-0.21", "-0.21", "支付时间",
                "2026-11-15 20:07:54", "2026-11-15 20:07:54", "付款方式", "亲情卡(示例)", "账单详情"));
        assertNotNull(discounted);
        assertEquals(79, discounted.cents());
        assertEquals(-1, discounted.direction());
        assertEquals("亲情卡(示例)", discounted.payment());
        assertTrue(discounted.order().isEmpty());
        assertNull(Bill.parse(Bill.ALIPAY, List.of("示例出行", "交易成功",
                "订单金额", "1.00", "碰一下立减", "-0.21", "支付时间", "2026-11-15 20:07:54", "账单详情")));
    }

    @Test public void alipayReadsDirectionPrefixAndIgnoresBusinessOrderInDescription() {
        var fields = new java.util.ArrayList<>(List.of("示例云服务", "支出10元", "交易成功", "支付时间",
                "2026-11-16 00:35:35", "付款方式", "示例银行储蓄卡(1234)", "商品说明",
                "充值:示例服务购买,业务交易号:CFPTEST1234567890", "收款方全称", "示例公司", "更多", "账单详情"));
        Bill expense = Bill.parse(Bill.ALIPAY, fields);
        assertNotNull(expense);
        assertEquals(1000, expense.cents());
        assertEquals(-1, expense.direction());
        assertEquals("示例云服务", expense.merchant());
        assertEquals("示例银行储蓄卡(1234)", expense.payment());
        assertEquals("1234", expense.lastFour());
        assertTrue(expense.order().isEmpty());

        fields.set(1, "收入： 10.00元");
        Bill income = Bill.parse(Bill.ALIPAY, fields);
        assertNotNull(income);
        assertEquals(1000, income.cents());
        assertEquals(1, income.direction());
        fields.add("支付宝交易号:ALIPAYTEST1234567890");
        assertEquals("ALIPAYTEST1234567890", Bill.parse(Bill.ALIPAY, fields).order());
    }

    @Test public void alipayRemovesDebitOrderActionFromTender() {
        Bill expense = Bill.parse(Bill.ALIPAY, List.of("示例商户", "支出144.74元", "交易成功",
                "支付时间", "2026-11-16 00:19:42", "付款方式", "网商银行储蓄卡(1234) 查看扣款顺序",
                "商品说明", "示例消费", "收单机构", "示例收单银行", "账单详情"));
        assertNotNull(expense);
        assertEquals(14474, expense.cents());
        assertEquals(-1, expense.direction());
        assertEquals("网商银行储蓄卡(1234)", expense.payment());
        assertEquals("1234", expense.lastFour());
        assertTrue(expense.order().isEmpty());
    }

    @Test public void wechatMiniProgramTradeDetailReadsWebViewFields() {
        var now = java.time.Instant.parse("2026-11-16T03:30:40Z");
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        List<String> nodes = List.of("返回", "京东", "设置", "京东", "喜欢", "等753万+人喜欢",
                "小程序", "点外卖领国补", "服务", "交易详情", "-10.30", "京东",
                "当前状态", "支付成功", "支付时间", "2026年11月16日 11:30:15",
                "商品", "京东-订单编号420000272420261116", "商户全称", "江苏京东信息技术有限公司",
                "收单机构", "财付通支付科技有限公司", "支付方式", "亲属卡(明哥)",
                "交易单号", "4200002724202611161234567890", "商户单号", "3712345678901",
                "交易服务", "对订单有疑惑", "发起群收款", "在此商户的交易", "申请电子凭证");
        Bill bill = Bill.parse(Bill.WECHAT, nodes);
        assertNotNull(bill);
        assertEquals("京东", bill.merchant());
        assertEquals(1030, bill.cents());
        assertEquals(-1, bill.direction());
        assertEquals("亲属卡(明哥)", bill.payment());
        assertEquals("4200002724202611161234567890", bill.order());
        assertEquals("2026年11月16日 11:30:15", bill.tradeTime());
        assertFalse(bill.successPage());
        assertTrue(bill.shouldPost(false, now, zone));
    }

    @Test public void wechatEnglishReceiptPageKeepsMerchantAndAccount() {
        var now = java.time.Instant.parse("2026-11-15T23:11:20Z");
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        List<String> nodes = List.of("匠工老面小笼包", "-6.00",
                "Current Status", "Payment successful",
                "Payment Time", "2026/11/16 07:10:53",
                "Products", "匠工老面小笼包",
                "Merchant", "商户_钟敏",
                "Acquirer", "拉卡拉支付股份有限公司",
                "Payment Method", "Balance",
                "Transaction Order No.", "4200002724202611161234567890",
                "Merchant Order No.", "3712345678902",
                "Merchant Mini Program 收钱吧", "Transaction Services", "Suspicious transaction");
        Bill bill = Bill.parse(Bill.WECHAT, nodes);
        assertNotNull(bill);
        assertEquals("商户_钟敏", bill.merchant());
        assertEquals(600, bill.cents());
        assertEquals(-1, bill.direction());
        assertEquals("零钱", bill.payment());
        assertEquals("4200002724202611161234567890", bill.order());
        assertEquals("2026/11/16 07:10:53", bill.tradeTime());
        assertTrue(bill.shouldPost(false, now, zone));
        assertEquals("当前状态：支付成功", Bill.wechatLabel("Current Status: Payment successful"));
        assertNull(Bill.parse(Bill.WECHAT, List.of("-6.00", "Products", "小笼包",
                "Current Status", "Payment failed")));
    }

    @Test public void alipayEnglishBillDetailKeepsAmountMerchantAndCardTail() {
        var now = java.time.Instant.parse("2026-11-15T21:34:10Z");
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        List<String> nodes = List.of("阿里云", "支出5元", "Transaction successful",
                "Payment time", "2026/11/16 05:33:33",
                "Payment method", "BOC Debit Card(3985) ",
                "Item description", "充值:阿里云服务购买,业务交易号:CFPTEST1234567890",
                "Bonus", "立即领取10积分",
                "Full name of payee", "阿里云计算有限公司",
                "More", "Bill Management", "Transactions Category", "Daily",
                "Included in statistics", "Notes", "Add", "Contact Merchant",
                "View transaction records", "Back", "Transaction Details", "View all", "Translate");
        Bill bill = Bill.parse(Bill.ALIPAY, nodes);
        assertNotNull(bill);
        assertEquals(500, bill.cents());
        assertEquals(-1, bill.direction());
        assertEquals("阿里云", bill.merchant());
        assertEquals("BOC Debit Card(3985)", bill.payment());
        assertEquals("3985", bill.lastFour());
        assertEquals("2026/11/16 05:33:33", bill.tradeTime());
        assertTrue(bill.order().isEmpty());
        // 没有交易单号但时间与付款方式齐备，按时间+金额+商家+付款方式去重后直接入账。
        assertTrue(bill.shouldPost(false, now, zone));
        assertFalse(bill.shouldPost(false, now.plusSeconds(360), zone));
        assertEquals("支付方式：余额", Bill.alipayLabel("Payment method: Balance"));
        assertNull(Bill.parse(Bill.ALIPAY, List.of("阿里云", "支出5元", "Payment failed",
                "Payment time", "2026/11/16 05:33:33")));
    }

    @Test public void alipayDetailWithoutOrderStillPosts() {
        var now = java.time.Instant.parse("2026-11-16T04:56:00Z");
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        List<String> nodes = List.of("账单详情", "阿里云", "支出10元", "交易成功", "支付时间",
                "2026-11-16 12:55:18", "付款方式", "网商银行储蓄卡(6627) ", "商品说明",
                "充值:阿里云服务购买,业务交易号:CFPTEST1234567890", "支付奖励", "立即领取10积分",
                "收款方全称", "阿里云计算有限公司", "更多", "账单管理", "已解锁日用百货贴纸",
                "账单分类", "日用百货", "标签", "请选择", "为您推荐", "日用品", "计入收支", "开关",
                "备注", "添加", "联系商家", "查看往来记录");
        Bill bill = Bill.parse(Bill.ALIPAY, nodes);
        assertNotNull(bill);
        assertEquals(1000, bill.cents());
        assertEquals(-1, bill.direction());
        assertEquals("阿里云", bill.merchant());
        assertEquals("网商银行储蓄卡(6627)", bill.payment());
        assertEquals("6627", bill.lastFour());
        assertEquals("2026-11-16 12:55:18", bill.tradeTime());
        assertTrue(bill.order().isEmpty());
        assertTrue(bill.shouldPost(false, now, zone));
        assertFalse(bill.shouldPost(false, now.plusSeconds(360), zone));
        assertTrue(bill.shouldPost(true, now.plusSeconds(360), zone));
    }

    @Test public void chineseSystemKeepsEnglishWordsAsData() {
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
        Bill bill = Bill.parse(Bill.WECHAT, List.of("Balance", "-6.00", "当前状态", "支付成功",
                "支付时间", "2026年11月16日 07:10:53", "支付方式", "零钱",
                "交易单号", "4200002724202611161234567890"));
        assertNotNull(bill);
        assertEquals("Balance", bill.merchant());
        assertEquals("零钱", bill.payment());
        assertEquals(600, bill.cents());
        assertFalse(Bill.englishInterface());
        assertTrue(Bill.englishPage(List.of("Current Status", "Payment successful")));
        Bill english = Bill.parse(Bill.WECHAT, List.of("匠工老面小笼包", "-6.00",
                "Current Status", "Payment successful", "Payment Time", "2026/11/16 07:10:53",
                "Payment Method", "Balance", "Transaction Order No.", "4200002724202611161234567890"));
        assertNotNull(english);
        assertEquals("零钱", english.payment());
        assertEquals("匠工老面小笼包", english.merchant());
    }

    @Test public void categoriesAreSingleLevelAndDirectionSpecific() {
        assertEquals(10, Categories.forDirection(-1).size());
        assertEquals(4, Categories.forDirection(1).size());
        assertFalse(Categories.forDirection(1).containsKey("food"));
        assertEquals("其他收入", Categories.label(Categories.UNKNOWN, 1));
        assertEquals("其他支出", Categories.label(Categories.UNKNOWN, -1));
        assertEquals("其他支出", Categories.label(null, -1));
        assertEquals("餐饮", Categories.label("food", -1));
        assertEquals("other_income", Categories.visibleId(null, 1));
        assertEquals("other_expense", Categories.visibleId(Categories.UNKNOWN, -1));
        assertFalse(Categories.forDirection(-1).containsKey(Categories.UNKNOWN));
    }
}
