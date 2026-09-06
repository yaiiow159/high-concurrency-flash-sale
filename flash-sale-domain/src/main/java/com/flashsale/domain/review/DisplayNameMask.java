package com.flashsale.domain.review;

/** 評價作者名稱的遮蔽。 */
public final class DisplayNameMask {

    private static final String STAR = "＊";
    private static final int MIN_STARS = 2;
    private static final String FALLBACK = "匿名用戶";

    private DisplayNameMask() {
    }

    /** 遮蔽顯示名稱。 */
    public static String apply(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return FALLBACK;
        }
        String trimmed = displayName.trim();
        String first = trimmed.substring(0, Math.min(1, trimmed.length()));
        int stars = Math.max(MIN_STARS, trimmed.length() - 1);
        return first + STAR.repeat(stars);
    }
}
