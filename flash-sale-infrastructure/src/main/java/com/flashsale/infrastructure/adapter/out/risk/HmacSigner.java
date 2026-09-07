package com.flashsale.infrastructure.adapter.out.risk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/** 資格憑證與驗證題共用的 HMAC。無狀態：驗證不查任何儲存。 */
final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    HmacSigner(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("flash-sale.risk.secret 至少 32 字元");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("計算簽章失敗", e);
        }
    }

    /** 常數時間比對：簽章比對不可以因為前幾個字元對了就提早回傳。 */
    boolean matches(String payload, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(payload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
