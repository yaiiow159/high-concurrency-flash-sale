package com.flashsale.application.port.in.dto;

import com.flashsale.domain.activity.SeckillActivity;

import java.math.BigDecimal;
import java.time.Instant;

/** 活動詳情，供商品頁展示；{@code availableStock} 取自 Redis 即時餘量。 */
public record ActivityView(
        Long activityId,
        Long productId,
        String productName,
        BigDecimal seckillPrice,
        int totalStock,
        long availableStock,
        int perUserLimit,
        Instant startAt,
        Instant endAt,
        String status,
        boolean purchasable,
        /**
         * 伺服器當下時間。
         *
         * <p>前端用它校正本地時鐘：客戶端時鐘可能偏差數分鐘，
         * 直接用 {@code Date.now()} 倒數會讓時鐘快的使用者提早狂打 API、
         * 慢的則錯過開賣。
         *
         * <p>放在活動回應裡而非另開端點，是因為前端本來就要取活動資料——
         * 多一個 {@code /server-time} 端點就多一次往返，而秒殺開賣前
         * 那一秒的往返最不該浪費。
         */
        Instant serverTime
) {

    public static ActivityView of(SeckillActivity activity, long availableStock, Instant now) {
        return new ActivityView(
                activity.id(),
                activity.productId(),
                activity.productName(),
                activity.seckillPrice(),
                activity.totalStock(),
                availableStock,
                activity.perUserLimit(),
                activity.period().startAt(),
                activity.period().endAt(),
                activity.status().name(),
                activity.isPurchasableAt(now) && availableStock > 0,
                now);
    }
}
