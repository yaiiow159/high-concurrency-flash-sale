package com.flashsale.infrastructure.adapter.out.risk;

import com.flashsale.application.port.out.ChallengeCodec;
import com.flashsale.infrastructure.config.RiskProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * 算術驗證題：{@code a + b}。題目與到期時間簽在 token 裡，驗證時重算答案，不查任何儲存。
 * 它擋的是「連網頁都不開的腳本」，不是決心破解的人——那是風險評分的事。
 * 一次性由 {@code ChallengeReplayGuard} 負責；這裡維持無狀態。
 */
@Component
public class HmacChallengeCodec implements ChallengeCodec {

    private static final String SEPARATOR = ".";
    private static final int PARTS = 4;
    private static final int MAX_OPERAND = 20;
    private static final int MAX_LENGTH = 256;

    private final HmacSigner signer;
    private final Duration ttl;
    /** 每執行緒一個：SecureRandom 是硬性序列化點，共用一個實例會讓 /challenge 不隨核心數擴展 */
    private final ThreadLocal<SecureRandom> random = ThreadLocal.withInitial(SecureRandom::new);

    public HmacChallengeCodec(RiskProperties properties) {
        this.signer = new HmacSigner(properties.secret());
        this.ttl = properties.challengeTtl();
    }

    @Override
    public Issued issue(Instant now) {
        SecureRandom rng = random.get();
        int a = 1 + rng.nextInt(MAX_OPERAND);
        int b = 1 + rng.nextInt(MAX_OPERAND);
        long expiresAt = now.plus(ttl).getEpochSecond();
        String payload = a + SEPARATOR + b + SEPARATOR + expiresAt;
        return new Issued(payload + SEPARATOR + signer.sign(payload), a + " + " + b + " = ?",
                Instant.ofEpochSecond(expiresAt));
    }

    @Override
    public boolean verify(String challengeToken, String answer, Instant now) {
        if (challengeToken == null || answer == null || challengeToken.length() > MAX_LENGTH) {
            return false;
        }
        String[] parts = challengeToken.split("\\.", -1);
        if (parts.length != PARTS) {
            return false;
        }
        String trimmedAnswer = answer.trim();
        // 先檢查是不是數字再解析，理由同 HmacQualificationTokenCodec
        if (!Digits.isUnsignedInt(parts[0]) || !Digits.isUnsignedInt(parts[1])
                || !Digits.isUnsignedLong(parts[2]) || !Digits.isUnsignedInt(trimmedAnswer)) {
            return false;
        }
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        long expiresAt = Long.parseLong(parts[2]);
        if (!signer.matches(a + SEPARATOR + b + SEPARATOR + expiresAt, parts[3])) {
            return false;
        }
        if (now.getEpochSecond() >= expiresAt) {
            return false;
        }
        return Integer.parseInt(trimmedAnswer) == a + b;
    }
}
