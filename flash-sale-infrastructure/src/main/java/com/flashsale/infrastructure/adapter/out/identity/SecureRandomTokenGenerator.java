package com.flashsale.infrastructure.adapter.out.identity;

import com.flashsale.application.port.out.SecureTokenGenerator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 以 {@link SecureRandom} 產生不可預測的令牌。 */
@Component
public class SecureRandomTokenGenerator implements SecureTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe 且無填充：令牌會出現在標頭與 JSON 中，不該需要額外轉義。
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String generateFamilyId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 規範要求必須提供的演算法，走到這裡代表 JVM 有問題。
            throw new IllegalStateException("執行環境缺少 SHA-256", e);
        }
    }
}
