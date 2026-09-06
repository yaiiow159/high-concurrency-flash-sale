package com.flashsale.domain.home;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;

/**
 * 版位的上架期間。
 *
 * <p>「當季限定」靠它自動上下架——沒有排程也沒有事件，讀取時比對現在時間即可。
 * 兩端都可為 null，代表沒有那一側的限制。
 */
public record Visibility(boolean enabled, Instant from, Instant to) {

    public Visibility {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "結束時間必須晚於開始時間");
        }
    }

    public static Visibility always() {
        return new Visibility(true, null, null);
    }

    public boolean isVisibleAt(Instant now) {
        return enabled
                && (from == null || !now.isBefore(from))
                && (to == null || now.isBefore(to));
    }
}
