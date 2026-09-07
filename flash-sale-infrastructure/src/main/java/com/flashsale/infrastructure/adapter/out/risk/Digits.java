package com.flashsale.infrastructure.adapter.out.risk;

/** 解析前先看是不是數字。靠 NumberFormatException 判斷會填一次堆疊，垃圾輸入會比合法輸入貴一個量級。 */
final class Digits {

    private static final int MAX_LONG_DIGITS = 18;
    private static final int MAX_INT_DIGITS = 9;

    private Digits() {
    }

    static boolean isUnsignedLong(String value) {
        return isDigits(value, MAX_LONG_DIGITS);
    }

    static boolean isUnsignedInt(String value) {
        return isDigits(value, MAX_INT_DIGITS);
    }

    private static boolean isDigits(String value, int maxDigits) {
        if (value == null || value.isEmpty() || value.length() > maxDigits) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
