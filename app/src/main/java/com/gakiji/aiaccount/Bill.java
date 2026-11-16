package com.gakiji.aiaccount;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record Bill(String source, String order, long cents, int direction, String merchant,
            String payment, String lastFour, String tradeTime, boolean successPage, String originalOrder) {
    Bill(String source, String order, long cents, int direction, String merchant,
         String payment, String lastFour, String tradeTime, boolean successPage) {
        this(source, order, cents, direction, merchant, payment, lastFour, tradeTime, successPage, "");
    }

    boolean refund() { return !originalOrder.isEmpty(); }

    String originalFingerprint() { return digest(source + "|" + originalOrder); }

    static final String WECHAT = "com.tencent.mm";
    static final String ALIPAY = "com.eg.android.AlipayGphone";
    private static final Pattern AMOUNT = Pattern.compile(
            "^(?:CNY\\s*)?[¥￥]?\\s*(\\d{1,9}(?:,\\d{3})*(?:\\.\\d{1,2})?)\\s*元?$");
    private static final Pattern TAIL = Pattern.compile("(?:尾号\\s*|[（(]\\s*|\\*{2,})(\\d{4})(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?i)(?:银行卡|储蓄卡|借记卡|信用卡|银行|尾号|\\*{2,}|card|bank)");
    private static final Pattern SUCCESS = Pattern.compile("^(支付成功|付款成功|交易成功|已支付|收款成功|已收款|已到账)$");

    static String label(String source) {
        return switch (source) {
            case WECHAT -> "微信";
            case ALIPAY -> "支付宝";
            default -> "手动";
        };
    }

    /**
     * 微信与支付宝的 H5 账单在英文系统下只提供英文标签与状态，先归一成中文再走同一套解析。
     */
    static String wechatLabel(String line) { return english(line, false); }

    static String alipayLabel(String line) { return english(line, true); }

    private static String english(String line, boolean alipay) {
        String text = line.strip();
        String direct = englishTerm(text, alipay);
        if (direct != null) return direct;
        int colon = text.indexOf(':');
        if (colon > 0) {
            String head = englishTerm(text.substring(0, colon).strip(), alipay);
            if (head != null) {
                String tail = text.substring(colon + 1).strip();
                String tailTerm = englishTerm(tail, alipay);
                return head + "：" + (tailTerm == null ? tail : tailTerm);
            }
        }
        return text;
    }

    private static String englishTerm(String text, boolean alipay) {
        return switch (text) {
            case "Current Status", "Transaction Status" -> "当前状态";
            case "Payment Time", "Payment time", "Transaction Time", "Transaction time",
                    "Payment Date" -> "支付时间";
            case "Payment Method", "Payment method", "Payment Type" -> "支付方式";
            case "Merchant", "Merchant Name", "Merchant name" -> "商户名称";
            case "Acquirer", "Acquiring Institution" -> "收单机构";
            case "Products", "Product", "Product Name" -> "商品";
            case "Item description", "Item Description" -> "商品说明";
            case "Full name of payee", "Payee full name", "Payee Name" -> "收款方全称";
            case "Bonus", "Rewards" -> "支付奖励";
            case "Bill Management" -> "账单管理";
            case "Transaction Details", "Bill Details", "Bill details" -> "账单详情";
            case "Transaction Services" -> "交易服务";
            case "Transaction Order No.", "Transaction Order No" -> "交易单号";
            case "Merchant Order No.", "Merchant Order No" -> "商户单号";
            case "Transaction No.", "Transaction Number", "Transaction ID", "Transaction id" ->
                    alipay ? "交易号" : "交易单号";
            case "Merchant ID", "Merchant No." -> "商户单号";
            case "Transaction successful", "Transaction Successful" -> "交易成功";
            case "Payment successful", "Payment Success", "Payment success" -> "支付成功";
            case "Payment failed", "Payment Failed", "Payment failure" -> "支付失败";
            case "Pending payment", "Awaiting payment" -> "待支付";
            case "Refunded", "Refund successful", "Refund Successful" -> "退款成功";
            case "Refund in progress", "Refunding" -> "退款中";
            case "Balance" -> alipay ? "余额" : "零钱";
            case "Yu'ebao", "Yu E Bao" -> "余额宝";
            case "Huabei" -> "花呗";
            default -> null;
        };
    }

    private static final Pattern ALIPAY_LABEL = Pattern.compile(
            "(?<![\\p{IsHan}A-Za-z0-9])(账单详情|当前状态|交易状态|订单状态|支付时间|付款时间|收款时间|交易时间|付款方式|支付方式|"
                    + "到账账户|收款方式|商品说明|收款方全称|商户全称|交易单号|支付宝交易号|订单号|交易号|"
                    + "实付金额|付款金额|交易金额|收款金额|账单管理|收单机构|清算机构|支付奖励|服务详情)(?:[：:]|(?=\\s|$))");
    private static final Pattern ALIPAY_DIRECTION_AMOUNT =
            Pattern.compile("^(支出|收入)[：:]?\\s*([¥￥]?\\s*[0-9,]+(?:[.][0-9]{1,2})?\\s*元?)$");
    private static final Pattern ALIPAY_SUCCESS = Pattern.compile("(?<![\\p{IsHan}])交易成功(?![\\p{IsHan}])");

    static Bill parse(String source, List<String> original) {
        if (!source.equals(WECHAT) && !source.equals(ALIPAY)) return null;
        boolean english = englishInterface();
        Bill bill = parse(source, original, english);
        // 系统语言是中文而账单页被应用单独设成英文时，页面证据足够才再试一次英文标签。
        if (bill == null && !english && englishPage(original)) bill = parse(source, original, true);
        return bill;
    }

    /** 系统语言不是中文时，微信与支付宝的 H5 账单会渲染成英文。 */
    static boolean englishInterface() {
        return !java.util.Locale.getDefault().getLanguage().startsWith("zh");
    }

    /** 页面上出现两个以上可识别的英文标签，才认为这一页真的被渲染成了英文。 */
    static boolean englishPage(List<String> original) {
        int hits = 0;
        for (String text : original) {
            for (String line : text.split("\\R")) {
                String value = line.strip();
                int colon = value.indexOf(':');
                if (englishTerm(value, true) != null
                        || (colon > 0 && englishTerm(value.substring(0, colon).strip(), true) != null)) {
                    if (++hits >= 2) return true;
                }
            }
        }
        return false;
    }

    static Bill parse(String source, List<String> original, boolean english) {
        if (!source.equals(WECHAT) && !source.equals(ALIPAY)) return null;
        List<String> lines = new ArrayList<>();
        for (String text : original) {
            if (source.equals(ALIPAY)) {
                text = text.replaceAll("[\\u00a0\\u202f\\u3000]", " ")
                        .replaceAll("[\\u200b\\u200e\\u200f\\ufeff]", "");
                text = ALIPAY_LABEL.matcher(text).replaceAll("\n$1：");
                text = text.replace("账单详情：", "账单详情\n");
                text = ALIPAY_SUCCESS.matcher(text).replaceAll("\n交易成功\n");
                text = text.replaceAll("(?m)^([^：:\\r\\n]*[\\p{IsHan}]) +([+＋−-]?[¥￥]? *[0-9,]+[.][0-9]{2}) *$", "$1\n$2");
            }
            for (String line : text.split("\\R")) {
                if (line.isBlank()) continue;
                String value = line.strip();
                if (source.equals(ALIPAY)) {
                    value = value.replaceFirst("[：:]$", "").strip();
                    var directional = ALIPAY_DIRECTION_AMOUNT.matcher(value);
                    if (directional.matches()) {
                        value = (directional.group(1).equals("收入") ? "+" : "-") + directional.group(2);
                    }
                    if (!value.isBlank()) lines.add(english ? alipayLabel(value) : value);
                } else lines.add(english ? wechatLabel(value) : value);
            }
        }
        for (String line : lines) {
            if (line.matches(".*(?:支付失败|支付中|待支付|等待付款|交易关闭|已取消|转账成功|提现成功|支付密码|付款码).*")
                    || line.matches("(?i).*(?:USD|HKD|JPY|EUR|美元|港币|日元|欧元).*")) return null;
        }

        String status = field(lines, "当前状态", "交易状态", "订单状态");
        String order = identifier(field(lines, source.equals(WECHAT),
                "交易单号", "订单号", "交易号", "支付宝交易号", "微信支付单号"));
        String time = field(lines, "支付时间", "交易时间", "付款时间", "收款时间");
        String payment = field(lines, "支付方式", "付款方式", "扣款账户", "收款方式", "到账账户");
        if (source.equals(ALIPAY)) {
            payment = payment.replaceFirst("\\s*查看扣款顺序\\s*[>›＞]?\\s*$", "").strip();
        }
        boolean alipayDetail = source.equals(ALIPAY) && lines.contains("账单详情") && !time.isEmpty()
                && (status.equals("交易成功") || (status.isEmpty() && lines.contains("交易成功")));
        String refundStatus = field(lines, "退款状态");
        String refundId = identifier(field(lines, "退款单号"));
        if (!refundId.isEmpty() || lines.contains("查看原订单") || (!refundStatus.isEmpty() && order.isEmpty())) {
            String amount = headerAmount(lines, false);
            if (!source.equals(WECHAT) || !refundStatus.matches("已退款|退款成功") || refundId.isEmpty()
                    || order.isEmpty() || !amount.startsWith("+")) return null;
            return new Bill(source, refundId, amountCents(amount.substring(1)), 1,
                    headerMerchant(lines, amount), field(lines, "退款方式"), "",
                    field(lines, "退款时间"), false, order);
        }

        String header = headerAmount(lines, alipayDetail);
        if (alipayDetail && header.isEmpty()) header = alipayAmount(lines);
        String receiptTime = field(lines, "收款时间");
        String title = headerMerchant(lines, header);
        String receipt = "";
        if (source.equals(WECHAT) && status.equals("已存入零钱") && !receiptTime.isEmpty() && header.startsWith("+")) {
            if (title.startsWith("微信红包")) receipt = "微信红包";
            else if (title.startsWith("群收款")) {
                receipt = "微信群收款";
                order = identifier(field(lines, true, "交易单号"));
            } else if (title.startsWith("转账") && title.matches("转账\\s*[-－—]?\\s*来自.+")) {
                receipt = "微信转账收款";
                order = identifier(field(lines, true, "转账单号", "交易单号"));
            }
            if (order.isEmpty()) receipt = "";
        }
        if (receipt.isEmpty() && lines.stream().anyMatch(line -> line.startsWith("微信红包") || line.equals("红包详情"))) return null;
        boolean wechatPurchase = source.equals(WECHAT) && !order.isEmpty() && !time.isEmpty()
                && !payment.isEmpty() && header.startsWith("-")
                && (status.isEmpty() || status.matches("已退款|部分退款|已部分退款|全额退款|已全额退款|退款成功"));
        if (!wechatPurchase && (lines.contains("已退款") || lines.contains("退款成功"))) return null;
        boolean paymentResult = lines.contains("支付成功") || lines.contains("付款成功")
                || (source.equals(WECHAT) && lines.contains("微信支付成功"));
        boolean successPage = status.isEmpty() && time.isEmpty() && !lines.contains("账单详情")
                && (paymentResult || lines.contains("收款成功") || lines.contains("已收款"));
        if (source.equals(WECHAT) && successPage && paymentResult) {
            header = headerAmount(lines, true);
            if (payment.equals("微信支付") || payment.equals("微信")) payment = "";
        }
        if (!(SUCCESS.matcher(status).matches() || successPage || alipayDetail || !receipt.isEmpty() || wechatPurchase)) return null;

        String directionValue = field(lines, "收支类型", "收/支", "收支");
        boolean income = !receipt.isEmpty() || directionValue.matches("收入|收款")
                || status.matches("收款成功|已收款|已到账") || lines.contains("收款成功") || lines.contains("已收款");
        boolean expense = directionValue.matches("支出|付款") || status.matches("支付成功|付款成功|已支付")
                || (successPage && paymentResult);
        String paid = field(lines, "实付金额", "实际支付", "实付款", "支付金额", "付款金额");
        String received = field(lines, "实收金额", "收款金额");
        if (!paid.isEmpty()) expense = true;
        if (!received.isEmpty()) income = true;
        String amount = expense ? paid : received;
        if (income && expense) return null;
        if (amount.isEmpty()) amount = field(lines, "交易金额", "金额");
        if (amount.isEmpty()) amount = header;
        if (alipayDetail && !amount.startsWith("+")) {
            if (income) return null;
            expense = true;
        }
        int direction = income ? 1 : expense ? -1 : amount.startsWith("+") ? 1 : amount.startsWith("-") ? -1 : 0;
        long cents = amountCents(amount.replaceFirst("^[+-]", ""));
        if (direction == 0 || cents <= 0 || (income && amount.startsWith("-"))
                || (expense && amount.startsWith("+"))) return null;
        String merchant = field(lines, "商家名称", "商户名称", "商家", "收款方", "付款方");
        if (merchant.isEmpty()) merchant = headerMerchant(lines, header);
        if (merchant.isEmpty() && source.equals(WECHAT)) merchant = trailingMerchant(lines, header);
        if (merchant.isEmpty()) merchant = field(lines, "商户全称", "收款方全称", "商品说明", "商品", "交易对方", "对方账户");
        if (!receipt.isEmpty()) {
            merchant = receipt;
            payment = "零钱";
            time = receiptTime;
        }
        Matcher tail = TAIL.matcher(payment);
        String lastFour = CARD.matcher(payment).find() && tail.find() ? tail.group(1) : "";
        return new Bill(source, order, cents, direction, shorten(merchant, 80),
                shorten(payment.replaceAll("\\d{5,}", "••••"), 80), lastFour, shorten(time, 40), successPage);
    }

    private static boolean alipayScreenAmount(String line) {
        return line.matches("[+-]?[¥￥]?\\s*(?:[0-9,]+[.][0-9]{2}\\s*元?|[0-9,]+\\s*元)");
    }

    private static String alipayAmount(List<String> lines) {
        var amounts = lines.stream().map(line -> line.replace('−', '-').replace('＋', '+'))
                .filter(Bill::alipayScreenAmount)
                .filter(line -> amountCents(line.replaceFirst("^[+-]", "")) > 0).distinct().toList();
        return amounts.size() == 1 ? amounts.get(0) : "";
    }

    private static String identifier(String text) {
        return text.matches("[A-Za-z0-9_-]{10,64}") ? text : "";
    }

    private static String headerAmount(List<String> lines, boolean unsigned) {
        var amounts = lines.stream().takeWhile(line -> !(unsigned && line.equals("交易成功")) && !line.matches(
                        "^(?:当前状态|交易状态|订单状态|退款状态|支付时间|交易时间|退款时间|收款时间|实付金额|支付方式|付款方式)(?:[:： ].*)?$"))
                .map(line -> line.replace('−', '-').replace('＋', '+'))
                .filter(line -> (line.matches("^[+¥￥-].*") || (unsigned && alipayScreenAmount(line)))
                        && amountCents(line.replaceFirst("^[+-]", "")) > 0).distinct().toList();
        return amounts.size() == 1 ? amounts.get(0) : "";
    }

    private static final Pattern CHROME = Pattern.compile(
            "账单详情|交易详情|交易服务|收款详情|服务|关闭|返回|完成|更多|设置|喜欢|小程序|全部账单|"
                    + "支付成功|微信支付成功|付款成功|交易成功|收款成功|已收款|已到账");

    private static String headerMerchant(List<String> lines, String amount) {
        for (int i = 1; !amount.isEmpty() && i < lines.size(); i++) {
            if (!lines.get(i).replace('−', '-').replace('＋', '+').equals(amount)) continue;
            String title = lines.get(i - 1);
            if (merchantName(title)) return shorten(title.replaceFirst("\\s*[-－]退款$", ""), 80);
        }
        return "";
    }

    /** 小程序交易详情把商户展示名放在金额下方，标题行只提供「交易详情」等界面文案。 */
    private static String trailingMerchant(List<String> lines, String amount) {
        for (int i = 0; !amount.isEmpty() && i + 1 < lines.size(); i++) {
            if (!lines.get(i).replace('−', '-').replace('＋', '+').equals(amount)) continue;
            String title = lines.get(i + 1);
            if (merchantName(title)) return shorten(title, 80);
        }
        return "";
    }

    private static boolean merchantName(String line) {
        return line.matches(".*[\\p{IsHan}A-Za-z].*") && !isLabel(line)
                && !CHROME.matcher(line).matches()
                && amountCents(line.replaceFirst("^[+-]", "")) <= 0;
    }

    static long amountCents(String value) {
        Matcher matcher = AMOUNT.matcher(value.strip());
        if (!matcher.matches()) return 0;
        try {
            return new BigDecimal(matcher.group(1).replace(",", "")).movePointRight(2).longValueExact();
        } catch (ArithmeticException invalid) {
            return 0;
        }
    }

    private static String field(List<String> lines, String... labels) {
        return field(lines, false, labels);
    }

    private static String field(List<String> lines, boolean joinOrderLines, String... labels) {
        for (String label : labels) {
            String found = "";
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String value = "";
                int next = i + 1;
                if (line.startsWith(label + "：") || line.startsWith(label + ":")
                        || line.startsWith(label + " ")) {
                    value = line.substring(label.length() + 1).strip();
                } else if (line.equals(label) && i + 1 < lines.size() && !isLabel(lines.get(i + 1))) {
                    value = lines.get(next++);
                }
                if (joinOrderLines) {
                    value = value.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
                    if (value.matches("\\d{24,48}") && next < lines.size() && lines.get(next).matches("\\d{1,16}")) {
                        value += lines.get(next);
                    }
                }
                if (!value.isEmpty()) {
                    if (!found.isEmpty() && !value.equals(found)) return "";
                    found = value;
                }
            }
            if (!found.isEmpty()) return found;
        }
        return "";
    }

    private static boolean isLabel(String line) {
        return line.matches("^(?:支付方式|付款方式|到账账户|转账单号|转账时间|转账说明|收款详情|收款时间|交易单号|订单号|交易号|支付时间|交易时间|商家|商户单号|收单机构|退款方式|退款单号|退款时间|退款状态|原订单|商品|商品说明|付款方|收款方|当前状态|交易状态|实付金额|交易金额|收支类型|收/支|商户全称|收款方全称)[:：]?$");
    }

    static String shorten(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    boolean shouldPost(boolean includeHistory, Instant now, ZoneId zone) {
        // 支付宝部分版本的账单详情不暴露交易单号，此时用「时间+金额+商家+付款方式」组成的
        // 指纹去重；只要时间与付款方式都在，页面证据已经完整，可以直接入账。
        boolean complete = !order.isEmpty()
                || source.equals(ALIPAY) && !successPage && !tradeTime.isBlank() && !payment.isBlank();
        if (!complete || (source.equals(WECHAT) && successPage && direction < 0 && payment.isBlank())) return false;
        if (successPage || includeHistory) return true;
        try {
            var format = DateTimeFormatter.ofPattern("uuuu-M-d H:mm[:ss]", java.util.Locale.ROOT)
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT);
            var time = LocalDateTime.parse(tradeTime.replace('/', '-').replace('年', '-').replace('月', '-').replace("日", ""), format).atZone(zone).toInstant();
            return !time.isBefore(now.minusSeconds(300)) && !time.isAfter(now.plusSeconds(30));
        } catch (DateTimeParseException unreadable) {
            return false;
        }
    }

    String fingerprint() {
        if (refund()) return digest(source + "|refund|" + order);
        return digest(source + "|" + order + (order.isEmpty()
                ? "|" + direction + "|" + cents + "|" + merchant + "|" + payment + "|" + tradeTime : ""));
    }

    private static String digest(String text) {
        try {
            byte[] data = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : data) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
