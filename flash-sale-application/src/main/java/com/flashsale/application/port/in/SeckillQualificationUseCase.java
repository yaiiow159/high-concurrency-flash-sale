package com.flashsale.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * 搶購資格預檢——削峰漏斗最上面那一層，在開賣前的冷路徑執行。
 * 成功代表：這個人此刻不是停權、不在黑名單、風險分數未達拒絕線、答對驗證題，
 * 並拿到一枚熱路徑只需純 CPU 就能驗的短效憑證。不代表他一定買得到。
 */
public interface SeckillQualificationUseCase {

    /** 發一道驗證題。無狀態：題目與到期時間一起簽在 token 裡。 */
    Challenge issueChallenge();

    Qualification qualify(QualifyCommand command);

    record Challenge(String challengeToken, String question, Instant expiresAt) {
    }

    record Qualification(String token, Instant expiresAt) {
    }

    record QualifyCommand(Long userId, Long activityId, String clientIp, String deviceId,
                          String challengeToken, String answer) {

        public QualifyCommand {
            Objects.requireNonNull(userId, "userId 不可為 null");
            Objects.requireNonNull(activityId, "activityId 不可為 null");
        }
    }
}
