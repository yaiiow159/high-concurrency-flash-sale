package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh token 的生命週期測試。
 *
 * <p>這些規則的錯誤不會讓任何功能「壞掉」——放寬了反而更順暢，
 * 只是同時把攻擊者也放進來了。因此每一條都要明確斷言。
 */
@DisplayName("Refresh token")
class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);

    @Test
    @DisplayName("剛簽發：可用於換取新令牌")
    void freshTokenIsUsable() {
        assertThat(issue().isUsableAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("已過期：不可用")
    void expiredTokenIsNotUsable() {
        RefreshToken token = issue();

        assertThat(token.isUsableAt(NOW.plus(TTL).plusSeconds(1))).isFalse();
        assertThat(token.isExpiredAt(NOW.plus(TTL))).isTrue();
    }

    @Test
    @DisplayName("到期當下即失效（右開區間）")
    void expiresExactlyAtDeadline() {
        assertThat(issue().isUsableAt(NOW.plus(TTL))).isFalse();
    }

    @Test
    @DisplayName("已撤銷：不可用")
    void revokedTokenIsNotUsable() {
        RefreshToken token = issue();
        token.revoke(NOW.plusSeconds(60));

        assertThat(token.isUsableAt(NOW.plusSeconds(120))).isFalse();
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("重複撤銷是安全的——登出與重用偵測可能同時發生")
    void revokeIsIdempotent() {
        RefreshToken token = issue();
        Instant first = NOW.plusSeconds(60);

        token.revoke(first);
        token.revoke(NOW.plusSeconds(120));

        // 保留第一次的時間才能正確還原事件順序
        assertThat(token.revokedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("輪替後即不可再用——這是重用偵測的基礎")
    void rotatedTokenIsNoLongerUsable() {
        RefreshToken token = issue();

        token.rotateTo("new-hash", NOW.plusSeconds(60));

        assertThat(token.isRotated()).isTrue();
        assertThat(token.isUsableAt(NOW.plusSeconds(120))).isFalse();
        assertThat(token.replacedByHash()).isEqualTo("new-hash");
    }

    @Test
    @DisplayName("不可輪替已輪替過的 token——漏了前置檢查要明確失敗，不可靜默放行")
    void cannotRotateTwice() {
        RefreshToken token = issue();
        token.rotateTo("hash-2", NOW.plusSeconds(60));

        assertThatThrownBy(() -> token.rotateTo("hash-3", NOW.plusSeconds(120)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("不可輪替已撤銷的 token")
    void cannotRotateRevokedToken() {
        RefreshToken token = issue();
        token.revoke(NOW.plusSeconds(60));

        assertThatThrownBy(() -> token.rotateTo("new-hash", NOW.plusSeconds(120)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("到期時間必須晚於簽發時間")
    void rejectsInvalidLifetime() {
        assertThatThrownBy(() -> RefreshToken.issue("h", 1L, "fam", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString 不可洩漏 tokenHash——它會出現在日誌裡")
    void toStringHidesTokenHash() {
        assertThat(issue().toString()).doesNotContain("secret-hash");
    }

    private static RefreshToken issue() {
        return RefreshToken.issue("secret-hash", 1L, "family-1", NOW, NOW.plus(TTL));
    }
}
