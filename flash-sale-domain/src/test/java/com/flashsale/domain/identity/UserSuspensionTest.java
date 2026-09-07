package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("停權")
class UserSuspensionTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    private static User user(UserRole role, UserStatus status) {
        return User.restore(1L, Email.of("a@example.com"), new PasswordHash("$2a$10$hash"),
                "A", role, status, NOW, 0L);
    }

    @Test
    @DisplayName("一般會員可停權，停權後既不能登入也不能下單")
    void suspendedCustomerCannotAuthenticateOrOrder() {
        User user = user(UserRole.CUSTOMER, UserStatus.ACTIVE);

        user.suspend();

        assertThat(user.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThatThrownBy(user::ensureCanAuthenticate)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
        assertThatThrownBy(user::ensureActive)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("管理員不可停權：兩個管理員互相停權，後台就沒人進得去")
    void adminCannotBeSuspended() {
        User admin = user(UserRole.ADMIN, UserStatus.ACTIVE);

        assertThatThrownBy(admin::suspend)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(admin.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("恢復後一切如常")
    void reactivateRestoresAccess() {
        User user = user(UserRole.CUSTOMER, UserStatus.SUSPENDED);

        user.reactivate();

        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThatCode(user::ensureActive).doesNotThrowAnyException();
    }
}
