package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.time.Instant;
import java.util.Objects;

/**
 * Refresh token 聚合根。
 *
 * <p><b>為什麼 access token 是 JWT，refresh token 卻不是？</b>
 * 因為兩者的需求正好相反：
 *
 * <table>
 *   <tr><th></th><th>Access token</th><th>Refresh token</th></tr>
 *   <tr><td>使用頻率</td><td>每個請求</td><td>每 15 分鐘一次</td></tr>
 *   <tr><td>需要</td><td>驗證零成本</td><td>可即時撤銷</td></tr>
 *   <tr><td>因此</td><td>自包含 JWT，不查儲存</td><td>不透明字串 + 查儲存</td></tr>
 * </table>
 *
 * <p>無狀態與可撤銷是互斥的。與其在兩者間妥協，不如把職責拆開：
 * 熱路徑用無狀態的短命令牌，撤銷需求落在低頻的長命令牌上。
 *
 * <h2>輪替與重用偵測</h2>
 *
 * <p>每次 refresh 都會發新的 token 並把舊的標記為「已輪替」。
 * 若有人拿<b>已輪替過</b>的 token 來換，代表這串 token 曾經外洩——
 * 合法用戶端不會保留舊 token，會這樣做的只有竊取者，或是被竊取後仍在用的原用戶端。
 *
 * <p>此時無法分辨誰是小偷，因此<b>整條輪替鏈（family）一併撤銷</b>，
 * 逼雙方重新登入。誤傷合法用戶端的代價是重登一次；
 * 放過的代價是攻擊者能無限期維持存取權。
 *
 * <pre>
 *   登入 ──► T1 ──refresh──► T2 ──refresh──► T3        （同一個 familyId）
 *            │
 *            └─ 有人重用 T1 ──► 偵測到 ──► 撤銷整個 family（T1/T2/T3 全失效）
 * </pre>
 */
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

    /**
     * 是否已被輪替掉。
     *
     * <p>拿這種 token 來換新令牌就是<b>重用</b>——合法用戶端不會保留它。
     */
    public boolean isRotated() {
        return replacedByHash != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * 輪替成新的 token。
     *
     * <p>只有可用的 token 能被輪替；對已撤銷或已輪替的 token 呼叫這個方法，
     * 代表呼叫端漏了前置檢查，直接拋錯而非靜默放行。
     */
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
