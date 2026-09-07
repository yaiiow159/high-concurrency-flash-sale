package com.flashsale.domain.risk;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * 搶購資格：在冷路徑把身分、黑名單、風險評分都做完之後發出的憑證。
 * 熱路徑只驗它——簽章正不正確、有沒有過期、是不是這個人與這檔活動的，全是純 CPU。
 */
public record QualificationToken(Long userId, Long activityId, Instant expiresAt, String nonce) {

    public QualificationToken {
        Objects.requireNonNull(userId, "userId 不可為 null");
        Objects.requireNonNull(activityId, "activityId 不可為 null");
        Objects.requireNonNull(expiresAt, "expiresAt 不可為 null");
        Objects.requireNonNull(nonce, "nonce 不可為 null");
    }

    /** 資格對不上人或活動時回同一個錯誤：不告訴呼叫端是哪一項對不上。 */
    public void ensureUsableBy(Long candidateUserId, Long candidateActivityId, Instant now) {
        if (!userId.equals(candidateUserId) || !activityId.equals(candidateActivityId)) {
            throw new BusinessException(ErrorCode.QUALIFICATION_INVALID);
        }
        if (!now.isBefore(expiresAt)) {
            throw new BusinessException(ErrorCode.QUALIFICATION_INVALID, "搶購資格已過期，請重新取得");
        }
    }
}
