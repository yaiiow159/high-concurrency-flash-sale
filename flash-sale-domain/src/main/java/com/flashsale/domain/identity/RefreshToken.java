package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/** Refresh token 聚合根。 */
public final class RefreshToken {

    private final Long id;
    /** 只存雜湊。儲存區外洩時，攻擊者拿到雜湊也無法用來換取新令牌。 */
    private final String tokenHash;
    private final Long userId;
    /** 同一次登入衍生出的所有 token 共用此識別，用於整條鏈一併撤銷。 */
    private final String familyId;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private Instant revokedAt;
    private String replacedByHash;

    private RefreshToken(Long id, String tokenHash, Long userId, String familyId,
                         Instant issuedAt, Instant expiresAt, Instant revokedAt, String replacedByHash) {
        this.id = id;
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash 不可為 null");
        this.userId = Objects.requireNonNull(userId, "userId 不可為 null");
        this.familyId = Objects.requireNonNull(familyId, "familyId 不可為 null");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt 不可為 null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt 不可為 null");
        this.revokedAt = revokedAt;
        this.replacedByHash = replacedByHash;
    }

    /** 登入時簽發，開啟一條新的輪替鏈。 */
    public static RefreshToken issue(String tokenHash, Long userId, String familyId,
                                     Instant issuedAt, Instant expiresAt) {
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt 必須晚於 issuedAt");
        }
        return new RefreshToken(null, tokenHash, userId, familyId, issuedAt, expiresAt, null, null);
    }

    public static RefreshToken restore(Long id, String tokenHash, Long userId, String familyId,
                                       Instant issuedAt, Instant expiresAt,
                                       Instant revokedAt, String replacedByHash) {
        return new RefreshToken(id, tokenHash, userId, familyId, issuedAt, expiresAt, revokedAt, replacedByHash);
    }

    /** 尚可用於換取新令牌：未撤銷、未過期、且未被輪替過。 */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && !isRotated() && now.isBefore(expiresAt);
    }

    /** 是否已被輪替掉。 */
    public boolean isRotated() {
        return replacedByHash != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 輪替成新的 token。 */
    public void rotateTo(String newTokenHash, Instant now) {
        if (!isUsableAt(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN,
                    "只有仍可用的 refresh token 能被輪替");
        }
        this.replacedByHash = Objects.requireNonNull(newTokenHash, "newTokenHash 不可為 null");
    }

    /** 撤銷。重複撤銷是安全的——登出與重用偵測可能同時發生。 */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public Long id() {
        return id;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Long userId() {
        return userId;
    }

    public String familyId() {
        return familyId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public String replacedByHash() {
        return replacedByHash;
    }

    /** 不輸出 tokenHash——它出現在日誌就等於降低了儲存區外洩的門檻。 */
    @Override
    public String toString() {
        return "RefreshToken{id=%s, userId=%d, family=%s, rotated=%s, revoked=%s}"
                .formatted(id, userId, familyId, isRotated(), isRevoked());
    }
}
