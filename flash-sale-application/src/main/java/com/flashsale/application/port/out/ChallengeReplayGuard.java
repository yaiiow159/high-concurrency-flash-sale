package com.flashsale.application.port.out;

import java.time.Duration;

/**
 * 驗證題一次性。實作必須保證：同一個 token 第一次呼叫回 true，之後在 ttl 內一律 false，
 * 且判斷是原子的——兩個併發請求不可同時拿到 true。
 * 沒有它，機器人解一次題就能拿著同一個 token 在 TTL 內無限次領資格。
 */
public interface ChallengeReplayGuard {

    boolean firstUse(String challengeToken, Duration ttl);
}
