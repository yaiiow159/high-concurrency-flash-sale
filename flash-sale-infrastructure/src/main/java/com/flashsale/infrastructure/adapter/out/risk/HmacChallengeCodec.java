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
 */
@Component
public class HmacChallengeCodec implements ChallengeCodec {

    private static final String SEPARATOR = ".";
    private static final int PARTS = 4;
    private static final int MAX_OPERAND = 20;

    private final HmacSigner signer;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public HmacChallengeCodec(RiskProperties properties) {
        this.signer = new HmacSigner(properties.secret());
        this.ttl = properties.challengeTtl();
    }

    @Override
    public Issued issue(Instant now) {
        int a = 1 + random.nextInt(MAX_OPERAND);
        int b = 1 + random.nextInt(MAX_OPERAND);
        long expiresAt = now.plus(ttl).getEpochSecond();
        String payload = a + SEPARATOR + b + SEPARATOR + expiresAt;
        return new Issued(payload + SEPARATOR + signer.sign(payload), a + " + " + b + " = ?",
                Instant.ofEpochSecond(expiresAt));
    }

    @Override
    public boolean verify(String challengeToken, String answer, Instant now) {
        if (challengeToken == null || answer == null) {
            return false;
        }
        String[] parts = challengeToken.split("\\.", -1);
        if (parts.length != PARTS) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            long expiresAt = Long.parseLong(parts[2]);
            if (!signer.matches(a + SEPARATOR + b + SEPARATOR + expiresAt, parts[3])) {
                return false;
            }
            if (now.getEpochSecond() >= expiresAt) {
                return false;
            }
            return Integer.parseInt(answer.trim()) == a + b;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
