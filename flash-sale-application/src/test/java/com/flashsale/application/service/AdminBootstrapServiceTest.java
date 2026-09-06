package com.flashsale.application.service;

import com.flashsale.application.port.in.AdminBootstrapUseCase.Outcome;
import com.flashsale.application.port.out.PasswordHasher;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.shared.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 初始管理員。 */
@DisplayName("初始管理員")
class AdminBootstrapServiceTest {

    private static final String EMAIL = "ops@example.com";
    private static final String PASSWORD = "password123";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private UserRepository userRepository;
    private AdminBootstrapService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        PasswordHasher hasher = mock(PasswordHasher.class);
        when(hasher.hash(any())).thenReturn(new PasswordHash("$2a$10$hashed"));
        service = new AdminBootstrapService(userRepository, hasher, CLOCK);
    }

    private static User customer(Long id) {
        return User.restore(id, Email.of(EMAIL), new PasswordHash("$2a$10$existing"),
                "既有使用者", UserRole.CUSTOMER, UserStatus.ACTIVE, CLOCK.instant(), 0L);
    }

    @Nested
    @DisplayName("守衛")
    class Guards {

        @Test
        @DisplayName("已經有管理員時什麼都不做")
        void skipsWhenAdminAlreadyExists() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);

            assertThat(service.bootstrap(EMAIL, PASSWORD, "營運")).isEqualTo(Outcome.SKIPPED);

            // **這是整個功能最重要的一條。** 每次啟動都套用設定的話，
            // 那份設定就是一條永久有效的提權後門：拿到環境變數的人
            // 隨時能把任意帳號變成管理員，而且看起來完全像正常啟動
            verify(userRepository, never()).update(any());
            verify(userRepository, never()).createIfAbsent(any());
        }

        @Test
        @DisplayName("已有管理員時，連查都不查那個信箱")
        void doesNotEvenLookUpTheEmail() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);

            service.bootstrap(EMAIL, PASSWORD, "營運");

            // 順序寫反（先查信箱再檢查有無管理員）不會有錯誤的結果，
            // 但會讓「這個信箱存不存在」變成一個可以從設定檔探測的事實
            verify(userRepository, never()).findByEmail(any());
        }

        @Test
        @DisplayName("密碼套用與一般註冊相同的政策")
        void enforcesPasswordPolicy() {
            // 政策只該有一處定義。這裡自己複製一份長度檢查的話，
            // 哪天政策改了，初始管理員會安靜地留在舊規則上
            assertThatThrownBy(() -> service.bootstrap(EMAIL, "short", "營運"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("密碼長度");

            verify(userRepository, never()).createIfAbsent(any());
        }

        @Test
        @DisplayName("密碼檢查發生在任何資料庫動作之前")
        void validatesBeforeTouchingTheDatabase() {
            assertThatThrownBy(() -> service.bootstrap(EMAIL, "short", "營運"))
                    .isInstanceOf(BusinessException.class);

            verify(userRepository, never()).existsByRole(any());
        }
    }

    @Nested
    @DisplayName("建立")
    class Creating {

        @Test
        @DisplayName("沒有這個信箱時建立新的管理員")
        void createsNewAdmin() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userRepository.createIfAbsent(any())).thenReturn(Optional.of(customer(9L)));

            assertThat(service.bootstrap(EMAIL, PASSWORD, "營運")).isEqualTo(Outcome.CREATED);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).update(saved.capture());
            assertThat(saved.getValue().role()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("兩個節點同時開機時，被唯一索引擋下的那個要失敗而不是靜默成功")
        void failsLoudlyOnConcurrentCreation() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
            when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
            when(userRepository.createIfAbsent(any())).thenReturn(Optional.empty());

            // 靜默成功的話，這個節點會回報「已建立管理員」，
            // 而它其實什麼都沒建——另一個節點建的那個才是真的
            assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD, "營運"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("提升")
    class Promoting {

        @Test
        @DisplayName("信箱已註冊過時提升為管理員")
        void promotesExistingAccount() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
            when(userRepository.findByEmail(any())).thenReturn(Optional.of(customer(3L)));

            assertThat(service.bootstrap(EMAIL, PASSWORD, "營運")).isEqualTo(Outcome.PROMOTED);

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).update(saved.capture());
            assertThat(saved.getValue().role()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("提升既有帳號時不覆寫密碼")
        void doesNotOverwritePassword() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
            when(userRepository.findByEmail(any())).thenReturn(Optional.of(customer(3L)));

            service.bootstrap(EMAIL, PASSWORD, "營運");

            // 順手重設密碼會讓這份設定多一個能力：覆寫任意既有帳號的密碼。
            // 那與「建立第一個管理員」是兩件事
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).update(saved.capture());
            assertThat(saved.getValue().passwordHash().value()).isEqualTo("$2a$10$existing");
        }

        @Test
        @DisplayName("不建立重複帳號")
        void doesNotCreateDuplicate() {
            when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
            when(userRepository.findByEmail(any())).thenReturn(Optional.of(customer(3L)));

            service.bootstrap(EMAIL, PASSWORD, "營運");

            verify(userRepository, never()).createIfAbsent(any());
        }
    }
}
