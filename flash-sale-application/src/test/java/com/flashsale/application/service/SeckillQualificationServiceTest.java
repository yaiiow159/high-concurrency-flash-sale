package com.flashsale.application.service;

import com.flashsale.application.config.QualificationSettings;
import com.flashsale.application.port.in.SeckillQualificationUseCase.Qualification;
import com.flashsale.application.port.in.SeckillQualificationUseCase.QualifyCommand;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.BlacklistRepository;
import com.flashsale.application.port.out.ChallengeCodec;
import com.flashsale.application.port.out.QualificationTokenCodec;
import com.flashsale.application.port.out.RiskSignalStore;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.identity.Email;
import com.flashsale.domain.identity.PasswordHash;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.identity.UserRole;
import com.flashsale.domain.identity.UserStatus;
import com.flashsale.domain.risk.BlacklistEntry;
import com.flashsale.domain.risk.QualificationToken;
import com.flashsale.domain.risk.RiskPolicy;
import com.flashsale.domain.risk.RiskSignals;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("搶購資格預檢")
class SeckillQualificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final long USER_ID = 88L;
    private static final long ACTIVITY_ID = 1001L;
    private static final QualificationSettings SETTINGS =
            new QualificationSettings(true, Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(3));

    private UserRepository userRepository;
    private ActivityRepository activityRepository;
    private BlacklistRepository blacklistRepository;
    private RiskSignalStore riskSignalStore;
    private ChallengeCodec challengeCodec;
    private QualificationTokenCodec tokenCodec;
    private SeckillMetrics metrics;
    private SeckillQualificationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        activityRepository = mock(ActivityRepository.class);
        blacklistRepository = mock(BlacklistRepository.class);
        riskSignalStore = mock(RiskSignalStore.class);
        challengeCodec = mock(ChallengeCodec.class);
        tokenCodec = mock(QualificationTokenCodec.class);
        metrics = mock(SeckillMetrics.class);

        when(challengeCodec.verify(anyString(), anyString(), any())).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.ACTIVE, NOW.minusSeconds(86_400))));
        when(blacklistRepository.findActive(anyLong(), any())).thenReturn(Optional.empty());
        // 活動 10 分鐘後開賣、持續一小時：在領資格窗口內
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity(NOW.plusSeconds(600), NOW.plusSeconds(4200))));
        when(riskSignalStore.observe(any())).thenReturn(new RiskSignals(86_400, 1, 1, 1));
        when(tokenCodec.issue(any())).thenReturn("signed");

        service = new SeckillQualificationService(userRepository, activityRepository, blacklistRepository,
                riskSignalStore, challengeCodec, tokenCodec, RiskPolicy.defaults(), SETTINGS, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(UserStatus status, Instant createdAt) {
        return User.restore(USER_ID, Email.of("u@example.com"), new PasswordHash("$2a$10$hash"), "U",
                UserRole.CUSTOMER, status, createdAt, 0L);
    }

    private static SeckillActivity activity(Instant startAt, Instant endAt) {
        return SeckillActivity.builder().id(ACTIVITY_ID).skuId(1L).productName("p")
                .seckillPrice(BigDecimal.TEN).totalStock(10).perUserLimit(1)
                .period(startAt, endAt).status(ActivityStatus.ONLINE).version(0L).build();
    }

    private static QualifyCommand command() {
        return new QualifyCommand(USER_ID, ACTIVITY_ID, "1.2.3.4", "dev-1", "challenge", "7");
    }

    private void assertRejected(ErrorCode code) {
        assertThatThrownBy(() -> service.qualify(command()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(code);
        verify(tokenCodec, never()).issue(any());
    }

    @Test
    @DisplayName("全部通過：憑證綁定這個人與這檔活動，到期時間取 tokenTtl 與活動結束較早者")
    void grantsToken() {
        Qualification result = service.qualify(command());

        ArgumentCaptor<QualificationToken> captor = ArgumentCaptor.forClass(QualificationToken.class);
        verify(tokenCodec).issue(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().activityId()).isEqualTo(ACTIVITY_ID);
        assertThat(captor.getValue().expiresAt()).isEqualTo(NOW.plus(SETTINGS.tokenTtl()));
        assertThat(result.token()).isEqualTo("signed");
        verify(metrics).recordQualification("granted");
    }

    @Test
    @DisplayName("憑證活不過活動結束：活動剩 5 分鐘就只給 5 分鐘")
    void tokenExpiresWithActivity() {
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity(NOW.minusSeconds(60), NOW.plusSeconds(300))));

        assertThat(service.qualify(command()).expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("答錯驗證題：連資料庫都不碰")
    void wrongAnswerShortCircuits() {
        when(challengeCodec.verify(anyString(), anyString(), any())).thenReturn(false);

        assertRejected(ErrorCode.CHALLENGE_FAILED);
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("停權者拿不到資格")
    void suspendedUserRejected() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(UserStatus.SUSPENDED, NOW.minusSeconds(86_400))));

        assertRejected(ErrorCode.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("黑名單拿不到資格——但這裡不擋登入，那是停權的事")
    void blacklistedRejected() {
        when(blacklistRepository.findActive(USER_ID, NOW))
                .thenReturn(Optional.of(new BlacklistEntry(USER_ID, "刷單", 1L, NOW.minusSeconds(60), null)));

        assertRejected(ErrorCode.USER_BLACKLISTED);
    }

    @Test
    @DisplayName("開賣前超過 leadTime：太早，憑證會在開賣前就過期")
    void tooEarlyRejected() {
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity(NOW.plusSeconds(3600), NOW.plusSeconds(7200))));

        assertRejected(ErrorCode.ACTIVITY_NOT_STARTED);
    }

    @Test
    @DisplayName("活動已結束：不發")
    void endedRejected() {
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity(NOW.minusSeconds(7200), NOW.minusSeconds(1))));

        assertRejected(ErrorCode.ACTIVITY_ENDED);
    }

    @Test
    @DisplayName("風險分數過線：拒絕，指標記下原因碼")
    void riskyRejected() {
        when(riskSignalStore.observe(any())).thenReturn(new RiskSignals(10, 50, 1, 1));

        assertRejected(ErrorCode.RISK_REJECTED);
        verify(metrics).recordQualification("risk_rejected");
    }
}
