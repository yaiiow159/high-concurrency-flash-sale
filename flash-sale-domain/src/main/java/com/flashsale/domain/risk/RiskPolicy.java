package com.flashsale.domain.risk;

/** 風險規則的門檻。數值來自設定，領域只負責用它們算分。 */
public record RiskPolicy(
        long youngAccountSeconds,
        int maxUsersPerIp,
        int maxUsersPerDevice,
        int maxQualificationsPerUser,
        int rejectScore
) {

    public RiskPolicy {
        if (rejectScore <= 0) {
            throw new IllegalArgumentException("rejectScore 必須大於 0");
        }
    }

    public static RiskPolicy defaults() {
        return new RiskPolicy(600, 20, 3, 5, 60);
    }
}
