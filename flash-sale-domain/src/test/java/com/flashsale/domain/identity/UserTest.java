package com.flashsale.domain.identity;

import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("使用者")
class UserTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Nested
    @DisplayName("電子郵件")
    class EmailRules {

        @Test
        @DisplayName("正規化為小寫——否則唯一索引擋不住大小寫不同的重複註冊")
        void normalizesToLowerCase() {
            assertThat(Email.of("Alice@Example.COM").value()).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("去除前後空白")
        void trimsWhitespace() {
            assertThat(Email.of("  bob@example.com  ").value()).isEqualTo("bob@example.com");
        }

        @ParameterizedTest
        @ValueSource(strings = {"no-at-sign", "@example.com", "a@b", "a b@example.com", "a@@b.com"})
        @DisplayName("格式不合法：拒絕")
        void rejectsMalformed(String candidate) {
            assertThatThrownBy(() -> Email.of(candidate)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("遮蔽後才可寫進日誌——完整信箱是個資")
        void masksForLogging() {
            assertThat(Email.of("alice@example.com").masked()).isEqualTo("a***e@example.com");
            // toString 也是遮蔽的，避免有人把整個物件塞進日誌樣板
            assertThat(Email.of("alice@example.com").toString()).doesNotContain("alice@");
        }

        @Test
        @DisplayName("極短的本地部分也不可洩漏")
        void masksShortLocalPart() {
            assertThat(Email.of("ab@example.com").masked()).isEqualTo("**@example.com");
        }
    }

    @Nested
    @DisplayName("帳號狀態")
    class AccountStatus {

        @Test
        @DisplayName("正常帳號可通過認證")
        void activeUserCanAuthenticate() {
            assertThatCode(() -> newUser().ensureCanAuthenticate()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("停權帳號拒絕認證")
        void suspendedUserCannotAuthenticate() {
            User user = newUser();
            user.suspend();

            assertThatThrownBy(user::ensureCanAuthenticate)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_SUSPENDED);
        }
    }

    @Nested
    @DisplayName("角色與權限")
    class Roles {

        @Test
        @DisplayName("新註冊者一律是消費者，不會意外拿到管理權限")
        void registersAsCustomer() {
            assertThat(newUser().role()).isEqualTo(UserRole.CUSTOMER);
        }

        @Test
        @DisplayName("消費者不具備管理 scope")
        void customerHasNoAdminScope() {
            assertThat(UserRole.CUSTOMER.scopes()).doesNotContain("seckill:admin");
        }

        @Test
        @DisplayName("管理員同時具備下單與管理 scope")
        void adminHasBothScopes() {
            assertThat(UserRole.ADMIN.scopeClaim()).isEqualTo("seckill:order seckill:admin");
        }
    }

    @Nested
    @DisplayName("建構期不變條件")
    class Invariants {

        @Test
        @DisplayName("顯示名稱不可為空")
        void rejectsBlankDisplayName() {
            assertThatThrownBy(() -> User.register(
                    Email.of("a@example.com"), hash(), "  ", NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("新註冊尚未持久化，id 為 null")
        void newUserIsNotPersisted() {
            assertThat(newUser().isPersisted()).isFalse();
        }

        @Test
        @DisplayName("toString 不可洩漏信箱全文與密碼雜湊")
        void toStringHidesSensitiveData() {
            User user = User.restore(1L, Email.of("alice@example.com"), hash(), "Alice",
                    UserRole.CUSTOMER, UserStatus.ACTIVE, NOW, 0L);

            assertThat(user.toString())
                    .doesNotContain("alice@example.com")
                    .doesNotContain("$2a$10$");
        }
    }

    private static User newUser() {
        return User.register(Email.of("alice@example.com"), hash(), "Alice", NOW);
    }

    private static PasswordHash hash() {
        return new PasswordHash("$2a$10$abcdefghijklmnopqrstuv");
    }
}
