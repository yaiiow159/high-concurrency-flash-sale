package com.flashsale.domain.risk;

/**
 * 領取搶購資格時觀察到的訊號。全部是「計數」而不是判斷，
 * 判斷交給 {@link RiskAssessment}——訊號怎麼收集是基礎設施的事，怎麼解讀是規則的事。
 */
public record RiskSignals(
        long accountAgeSeconds,
        int usersOnSameIp,
        int usersOnSameDevice,
        int recentQualificationsByUser
) {
}
