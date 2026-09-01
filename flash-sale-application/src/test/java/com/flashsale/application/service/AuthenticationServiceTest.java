package com.flashsale.application.service;

import com.flashsale.application.config.AuthPolicy;
import com.flashsale.application.port.in.command.LoginCommand;
import com.flashsale.application.port.in.dto.SessionTokens;
import com.flashsale.application.port.out.AccessTokenIssuer;
import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.application.port.out.RefreshTokenRepository;
import com.flashsale.application.port.out.SecureTokenGenerator;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.RefreshToken;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 認證流程的單元測試。
 *
 * <p>這裡驗證的多數規則<b>放寬了系統反而更順暢</b>——
 * 不做假比對更快、不輪替更簡單、重用不撤銷體驗更好。
 * 正因如此，它們特別容易在某次「順手優化」中被拿掉，
 * 所以每一條都必須有一支測試明確擋著。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("認證")
class AuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Long USER_ID = 42L;
    private static final String RAW_REFRESH = "raw-refresh-token";
    private static final String REFRESH_HASH = "hash-of-raw-refresh-token";

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordHasher passwordHasher;
    @Mock private AccessTokenIssuer accessTokenIssuer;
    @Mock private SecureTokenGenerator tokenGenerator;
    @Mock private RefreshTokenRevoker tokenRevoker;

    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(userRepository, refreshTokenRepository, passwordHasher,
                accessTokenIssuer, tokenGenerator, tokenRevoker,
                new AuthPolicy(Duration.ofMinutes(15), Duration.ofDays(7)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(accessTokenIssuer.issue(any()))
                .thenReturn(new AccessTokenIssuer.IssuedAccessToken("jwt-value", Duration.ofMinutes(15)));
        when(tokenGenerator.generateToken()).thenReturn("new-raw-token");
        when(tokenGenerator.generateFamilyId()).thenReturn("family-1");
        when(tokenGenerator.hashToken(RAW_REFRESH)).thenReturn(REFRESH_HASH);
        when(tokenGenerator.hashToken("new-raw-token")).thenReturn("new-hash");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("登入")
    class Login {

        @Test
        @DisplayName("帳密正確：發出令牌組")
        void issuesTokensOnValidCredentials() {
            givenUser(UserStatus.ACTIVE);
            when(passwordHasher.matches(eq("correct"), any())).thenReturn(true);

            SessionTokens tokens = service.login(new LoginCommand("a@example.com", "correct"));

            assertThat(tokens.accessToken()).isEqualTo("jwt-value");
            assertThat(tokens.refreshToken()).isEqualTo("new-raw-token");
            assertThat(tokens.tokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("密碼錯誤：回 INVALID_CREDENTIALS")
        void rejectsWrongPassword() {
            givenUser(UserStatus.ACTIVE);
            when(passwordHasher.matches(anyString(), any())).thenReturn(false);

            assertThatThrownBy(() -> service.login(new LoginCommand("a@example.com", "wrong")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("信箱不存在：錯誤碼與密碼錯誤相同，不洩漏帳號是否存在")
        void doesNotRevealWhetherAccountExists() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", "x")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("信箱不存在時仍執行假比對——否則回應時間會洩漏哪些信箱已註冊")
        void wastesTimeWhenAccountMissing() {
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", "x")))
                    .isInstanceOf(BusinessException.class);

            // 這支測試擋的是「順手把沒用的呼叫刪掉」這種優化
            verify(passwordHasher).wasteTime();
        }

        @Test
        @DisplayName("帳號已停權：拒絕登入")
        void rejectsSuspendedAccount() {
            givenUser(UserStatus.SUSPENDED);
            when(passwordHasher.matches(anyString(), any())).thenReturn(true);

            assertThatThrownBy(() -> service.login(new LoginCommand("a@example.com", "correct")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
        }

        @Test
        @DisplayName("每次登入開新的輪替鏈，不同裝置互不影響")
        void startsNewFamilyPerLogin() {
            givenUser(UserStatus.ACTIVE);
            when(passwordHasher.matches(anyString(), any())).thenReturn(true);

            service.login(new LoginCommand("a@example.com", "correct"));

            verify(tokenGenerator).generateFamilyId();
        }
    }

    @Nested
    @DisplayName("續期")
    class Refresh {

        @Test
        @DisplayName("有效 token：發新組並輪替掉舊的")
        void rotatesOnRefresh() {
            RefreshToken stored = usableToken();
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(stored));
            givenUserById(UserStatus.ACTIVE);

            SessionTokens tokens = service.refresh(RAW_REFRESH);

            assertThat(tokens.refreshToken()).isEqualTo("new-raw-token");
            // 舊 token 必須被標記為已輪替，這是重用偵測的基礎
            assertThat(stored.isRotated()).isTrue();
            assertThat(stored.replacedByHash()).isEqualTo("new-hash");
        }

        @Test
        @DisplayName("續期沿用同一條輪替鏈")
        void keepsSameFamily() {
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(usableToken()));
            givenUserById(UserStatus.ACTIVE);

            service.refresh(RAW_REFRESH);

            // 續期不該開新鏈，否則重用偵測會失去追溯範圍
            verify(tokenGenerator, never()).generateFamilyId();
        }

        @Test
        @DisplayName("重用已輪替的 token：撤銷整條輪替鏈並拒絕")
        void detectsReuseAndRevokesFamily() {
            RefreshToken rotated = usableToken();
            rotated.rotateTo("some-newer-hash", NOW.minusSeconds(60));
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(rotated));

            assertThatThrownBy(() -> service.refresh(RAW_REFRESH))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);

            // 這是本類別最重要的一條斷言：偵測到外洩就讓整條鏈失效。
            //
            // 必須透過 RefreshTokenRevoker（獨立交易）而非直接呼叫 repository——
            // 本方法結尾會拋例外，直接呼叫的話撤銷會被外層交易回滾掉，
            // 變成「偵測到外洩卻什麼都沒撤銷」。這個 bug 實機驗證才發現，
            // 這條斷言是為了防止有人把它「簡化」回去。
            verify(tokenRevoker).revokeFamily("family-1", NOW);
            verify(refreshTokenRepository, never()).revokeFamily(anyString(), any());
        }

        @Test
        @DisplayName("已撤銷的 token：拒絕")
        void rejectsRevokedToken() {
            RefreshToken revoked = usableToken();
            revoked.revoke(NOW.minusSeconds(60));
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(revoked));

            assertThatThrownBy(() -> service.refresh(RAW_REFRESH))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("已過期的 token：拒絕")
        void rejectsExpiredToken() {
            RefreshToken expired = RefreshToken.restore(1L, REFRESH_HASH, USER_ID, "family-1",
                    NOW.minus(Duration.ofDays(8)), NOW.minusSeconds(1), null, null);
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> service.refresh(RAW_REFRESH))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("查無此 token：拒絕，且不撤銷任何東西")
        void rejectsUnknownToken() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refresh(RAW_REFRESH))
                    .isInstanceOf(BusinessException.class);
            verify(tokenRevoker, never()).revokeFamily(anyString(), any());
        }

        @Test
        @DisplayName("續期時重新檢查帳號狀態——停權後不該還能靠舊 token 續下去")
        void rechecksAccountStatusOnRefresh() {
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(usableToken()));
            givenUserById(UserStatus.SUSPENDED);

            assertThatThrownBy(() -> service.refresh(RAW_REFRESH))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
        }
    }

    @Nested
    @DisplayName("登出")
    class Logout {

        @Test
        @DisplayName("撤銷該 token")
        void revokesToken() {
            RefreshToken stored = usableToken();
            when(refreshTokenRepository.findByTokenHash(REFRESH_HASH)).thenReturn(Optional.of(stored));

            service.logout(RAW_REFRESH);

            assertThat(stored.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(stored);
        }

        @Test
        @DisplayName("無效 token 靜默忽略——回報它不存在等於提供一支驗證 token 的 API")
        void silentlyIgnoresUnknownToken() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            service.logout(RAW_REFRESH);

            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("空字串不觸發任何查詢")
        void ignoresBlankToken() {
            service.logout("  ");

            verify(refreshTokenRepository, never()).findByTokenHash(anyString());
        }
    }

    // ---- fixtures ----

    private void givenUser(UserStatus status) {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(user(status)));
    }

    private void givenUserById(UserStatus status) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(status)));
    }

    private static User user(UserStatus status) {
        return User.restore(USER_ID, Email.of("a@example.com"),
                new PasswordHash("$2a$10$hash"), "Alice",
                UserRole.CUSTOMER, status, NOW.minusSeconds(3600), 0L);
    }

    private static RefreshToken usableToken() {
        return RefreshToken.restore(1L, REFRESH_HASH, USER_ID, "family-1",
                NOW.minusSeconds(600), NOW.plus(Duration.ofDays(7)), null, null);
    }
}
