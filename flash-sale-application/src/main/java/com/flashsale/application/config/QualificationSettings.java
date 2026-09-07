package com.flashsale.application.config;

import java.time.Duration;

/**
 * 資格預檢的時間參數。
 * requireQualification 關掉時熱路徑不驗憑證——只給壓測與既有腳本用，正式環境不該關。
 */
public record QualificationSettings(
        boolean requireQualification,
        Duration tokenTtl,
        Duration leadTime,
        Duration challengeTtl
) {

    public QualificationSettings {
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalArgumentException("tokenTtl 必須為正值");
        }
        if (leadTime == null || leadTime.isNegative()) {
            throw new IllegalArgumentException("leadTime 不可為負值");
        }
        if (challengeTtl == null || challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("challengeTtl 必須為正值");
        }
    }

    public static QualificationSettings defaults() {
        return new QualificationSettings(true, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(3));
    }
}
