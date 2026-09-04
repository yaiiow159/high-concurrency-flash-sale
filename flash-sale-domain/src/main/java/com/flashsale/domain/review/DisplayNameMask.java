package com.flashsale.domain.review;

/**
 * 評價作者名稱的遮蔽。
 *
 * <h2>在寫入時遮，不是在畫面上遮</h2>
 *
 * <p>前端遮蔽等於完整姓名仍然出現在 API 回應裡，開一下開發者工具就看得到。
 * 遮蔽必須發生在資料離開伺服器<b>之前</b>——而最徹底的做法是
 * 讓完整姓名根本不進評價表（ADR-0014 決策 6）。
 *
 * <h2>規則刻意簡單，而且對短名字有下限</h2>
 *
 * <p>兩個字以下的名字（「王」「小明」）遮起來會變成「王」或「小＊」，
 * 前者等於沒遮、後者只剩一個字。因此一律至少保留首字、
 * 其餘一律換成全形星號，長度不足時補到兩個星號——
 * 讓「李四」與「李」在畫面上長得一樣，反而增加了不可辨識性。
 *
 * <p>用全形星號而不是半形：中文名字旁邊接半形星號會讓寬度對不齊，
 * 一整排評價看起來像是排版壞了。
 */
public final class DisplayNameMask {

    private static final String STAR = "＊";
    private static final int MIN_STARS = 2;
    private static final String FALLBACK = "匿名用戶";

    private DisplayNameMask() {
    }

    /**
     * 遮蔽顯示名稱。
     *
     * <p>空名字回「匿名用戶」而不是空字串：評價列表上一個沒有作者的項目
     * 看起來像是資料壞了，而使用者沒填暱稱是很正常的事。
     */
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
