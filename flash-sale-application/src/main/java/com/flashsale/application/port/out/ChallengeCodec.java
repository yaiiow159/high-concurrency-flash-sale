package com.flashsale.application.port.out;

import java.time.Instant;

/**
 * 驗證題的簽發與核對。實作必須無狀態：題目與到期時間簽在 token 裡，
 * 不寫任何儲存——開賣前一分鐘幾十萬人同時領題，寫 Redis 只是把尖峰往前搬。
 */
public interface ChallengeCodec {

    Issued issue(Instant now);

    /** 簽章、到期、答案任一不對都回 false。 */
    boolean verify(String challengeToken, String answer, Instant now);

    record Issued(String challengeToken, String question, Instant expiresAt) {
    }
}
