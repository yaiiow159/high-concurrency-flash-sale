package com.flashsale.application.service;

import com.flashsale.application.port.in.dto.UserView;
import com.flashsale.application.port.out.RefreshTokenRepository;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("後台會員管理")
class UserAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final long OPERATOR = 1L;
    private static final long TARGET = 2L;

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        when(userRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserAdminService(userRepository,
                new RefreshTokenRevoker(refreshTokenRepository), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(long id, UserRole role, UserStatus status) {
        return User.restore(id, Email.of("u" + id + "@example.com"), new PasswordHash("$2a$10$hash"),
                "使用者" + id, role, status, NOW.minusSeconds(3600), 0L);
    }

    @Test
    @DisplayName("停權：狀態改成 SUSPENDED，而且撤銷所有 refresh token——不撤銷的話停權要等到他自己登出")
    void suspendRevokesTokens() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, UserRole.CUSTOMER, UserStatus.ACTIVE)));
        when(refreshTokenRepository.revokeAllForUser(TARGET, NOW)).thenReturn(3);

        UserView view = service.suspend(TARGET, OPERATOR);

        assertThat(view.status()).isEqualTo("SUSPENDED");
        verify(refreshTokenRepository).revokeAllForUser(TARGET, NOW);
    }

    @Test
    @DisplayName("不可停權自己：否則最後一個管理員可以把自己鎖在門外")
    void cannotSuspendSelf() {
        assertThatThrownBy(() -> service.suspend(OPERATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(userRepository, never()).update(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any());
    }

    @Test
    @DisplayName("不可停權管理員：領域規則，服務層不繞過")
    void cannotSuspendAdmin() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, UserRole.ADMIN, UserStatus.ACTIVE)));

        assertThatThrownBy(() -> service.suspend(TARGET, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(userRepository, never()).update(any());
        verify(refreshTokenRepository, never()).revokeAllForUser(anyLong(), any());
    }

    @Test
    @DisplayName("恢復：回到 ACTIVE")
    void reactivate() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, UserRole.CUSTOMER, UserStatus.SUSPENDED)));

        assertThat(service.reactivate(TARGET).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("搜尋：狀態字串不合法是參數錯誤，不是空結果")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.search(null, "BANNED", 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PARAMETER);
    }
}
