package com.gakiji.aiaccount;

final class PaymentAliases {
    private static final String SEPARATORS = "[、,，;；\\r\\n]+";

    static String save(String input) {
        return String.join("、", java.util.Arrays.stream(input.split(SEPARATORS))
                .map(String::strip).filter(alias -> !alias.isEmpty()).distinct().toList());
    }

    static int match(String aliases, String payment) {
        String paid = normalize(payment);
        if (paid.isEmpty()) return 0;
        String familyCard = paid.replaceFirst("^(亲属卡|亲情卡)\\(.+\\)$", "$1");
        int score = 0;
        for (String raw : aliases.split(SEPARATORS)) {
            String alias = normalize(raw);
            if (alias.equals(paid)) return 2;
            if (!familyCard.equals(paid) && alias.equals(familyCard)) score = 1;
        }
        return score;
    }

    private static String normalize(String text) {
        return text.strip().replace('（', '(').replace('）', ')')
                .replaceFirst("\\s*\\(", "(")
                .replaceFirst("\\s*查看扣款顺序\\s*[>›＞]?\\s*$", "");
    }
}
