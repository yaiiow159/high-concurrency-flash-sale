package com.flashsale.infrastructure.adapter.out.risk;

import com.flashsale.application.port.out.QualificationTokenCodec;
import com.flashsale.domain.risk.QualificationToken;
import com.flashsale.infrastructure.config.RiskProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 憑證格式：{@code userId.activityId.expiresAtEpochSeconds.nonce.signature}。
 * 純字串運算與一次 HMAC，跑在熱路徑上也不會有任何 I/O。
 */
@Component
public class HmacQualificationTokenCodec implements QualificationTokenCodec {

    private static final String SEPARATOR = ".";
    private static final int PARTS = 5;
    private static final int MAX_LENGTH = 512;

    private final HmacSigner signer;

    public HmacQualificationTokenCodec(RiskProperties properties) {
        this.signer = new HmacSigner(properties.secret());
    }

    @Override
    public String issue(QualificationToken token) {
        String payload = payload(token.userId(), token.activityId(), token.expiresAt().getEpochSecond(), token.nonce());
        return payload + SEPARATOR + signer.sign(payload);
    }

    @Override
    public Optional<QualificationToken> verify(String encoded) {
        if (encoded == null || encoded.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != PARTS) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(parts[0]);
            long activityId = Long.parseLong(parts[1]);
            long expiresAt = Long.parseLong(parts[2]);
            String nonce = parts[3];
            if (nonce.isEmpty() || !signer.matches(payload(userId, activityId, expiresAt, nonce), parts[4])) {
                return Optional.empty();
            }
            return Optional.of(new QualificationToken(userId, activityId, Instant.ofEpochSecond(expiresAt), nonce));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String payload(long userId, long activityId, long expiresAt, String nonce) {
        return userId + SEPARATOR + activityId + SEPARATOR + expiresAt + SEPARATOR + nonce;
    }
}
