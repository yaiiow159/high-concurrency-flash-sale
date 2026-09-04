package com.flashsale.domain.promotion;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 一筆已套用的折扣。
 *
 * <p><b>{@code name} 是快照，不是引用。</b> 優惠會下架、券會過期、規則會改，
 * 而三個月後的客訴要看的是<b>當時</b>的名稱與金額。
 * 存 {@code promotionId} 讓畫面自己去查，就是 ADR-0007 已經解決過的同一個錯誤。
 *
 * <p>同時保留 {@code sourceId}：名稱回答「當時折的是什麼」，
 * ID 回答「那是哪一個優惠」。兩者缺一，前者無法追溯，後者無法還原。
 *
 * @param amount 折抵金額，<b>正數</b>。用負數表示折扣會讓每個讀到它的人
 *               都要先確認一次符號，而總有一次會弄反
 */
public record AppliedDiscount(
        DiscountType type,
        Long sourceId,
        String name,
        BigDecimal amount
) {

    public AppliedDiscount {
        Objects.requireNonNull(type, "type 不可為 null");
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "折扣名稱不可為空");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "折抵金額必須為正數，用負數表示折扣遲早會有人把符號弄反");
        }
    }
}
