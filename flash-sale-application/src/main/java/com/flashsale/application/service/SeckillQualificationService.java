package com.flashsale.application.service;

import com.flashsale.application.port.out.SeckillMetrics;
import com.flashsale.application.config.QualificationSettings;
import com.flashsale.application.port.in.SeckillQualificationUseCase;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.BlacklistRepository;
import com.flashsale.application.port.out.ChallengeCodec;
import com.flashsale.application.port.out.ChallengeReplayGuard;
import com.flashsale.application.port.out.QualificationTokenCodec;
import com.flashsale.application.port.out.RiskSignalStore;
import com.flashsale.application.port.out.UserRepository;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.identity.User;
import com.flashsale.domain.risk.QualificationToken;
import com.flashsale.domain.risk.RiskAssessment;
import com.flashsale.domain.risk.RiskPolicy;
import com.flashsale.domain.risk.RiskSignals;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 資格預檢：削峰漏斗最上面那一層。這裡可以讀資料庫、可以慢——
 * 它在開賣前跑，把熱路徑上做不起的檢查全部搬到這裡做完。
 *
 * 刻意沒有 {@code @Transactional}：兩次主鍵讀各自獨立，而中間有好幾次 Redis 往返，
 * 包進交易等於把 Redis 的延遲換算成 MySQL 連線的佔用時間——Redis 一慢，連線池就被抽乾，
 * 拖垮的是全站所有需要 MySQL 的端點。
 */
@Service
public class SeckillQualificationService implements SeckillQualificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(SeckillQualificationService.class);

    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final BlacklistRepository blacklistRepository;
    private final RiskSignalStore riskSignalStore;
    private final ChallengeCodec challengeCodec;
    private final ChallengeReplayGuard replayGuard;
    private final QualificationTokenCodec tokenCodec;
    private final RiskPolicy riskPolicy;
    private final QualificationSettings settings;
    private final SeckillMetrics metrics;
    private final Clock clock;

    public SeckillQualificationService(UserRepository userRepository,
                                       ActivityRepository activityRepository,
                                       BlacklistRepository blacklistRepository,
                                       RiskSignalStore riskSignalStore,
                                       ChallengeCodec challengeCodec,
                                       ChallengeReplayGuard replayGuard,
                                       QualificationTokenCodec tokenCodec,
                                       RiskPolicy riskPolicy,
                                       QualificationSettings settings,
                                       SeckillMetrics metrics,
                                       Clock clock) {
        this.userRepository = userRepository;
        this.activityRepository = activityRepository;
        this.blacklistRepository = blacklistRepository;
        this.riskSignalStore = riskSignalStore;
        this.challengeCodec = challengeCodec;
        this.replayGuard = replayGuard;
        this.tokenCodec = tokenCodec;
        this.riskPolicy = riskPolicy;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Challenge issueChallenge() {
        ChallengeCodec.Issued issued = challengeCodec.issue(clock.instant());
        return new Challenge(issued.challengeToken(), issued.question(), issued.expiresAt());
    }

    @Override
    public Qualification qualify(QualifyCommand command) {
        Instant now = clock.instant();
        try {
            Qualification qualification = evaluate(command, now);
            metrics.recordQualification("granted");
            return qualification;
        } catch (BusinessException e) {
            metrics.recordQualification(e.errorCode().name().toLowerCase(Locale.ROOT));
            throw e;
        }
    }

    private Qualification evaluate(QualifyCommand command, Instant now) {
        // 驗證題最先核對：答錯的人連資料庫都不用碰。答對的才記一次性——
        // 反過來的話，答錯一次就把題目作廢，使用者手滑得重領
        if (!challengeCodec.verify(command.challengeToken(), command.answer(), now)) {
            throw new BusinessException(ErrorCode.CHALLENGE_FAILED);
        }
        if (!replayGuard.firstUse(command.challengeToken(), settings.challengeTtl())) {
            throw new BusinessException(ErrorCode.CHALLENGE_FAILED, "這道題已經用過了，請重新領題");
        }
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.ensureActive();
        blacklistRepository.findActive(user.id(), now).ifPresent(entry -> {
            throw new BusinessException(ErrorCode.USER_BLACKLISTED);
        });

        SeckillActivity activity = activityRepository.findById(command.activityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));
        ensureWithinQualificationWindow(activity, now);

        RiskSignals signals = riskSignalStore.observe(new RiskSignalStore.Observation(
                user.id(), activity.id(), command.clientIp(), command.deviceId(), user.createdAt(), now));
        RiskAssessment assessment = RiskAssessment.evaluate(signals, riskPolicy);
        if (assessment.rejected()) {
            // 記到 warn 而不是回給使用者：告訴他被哪一條擋下，等於教他怎麼繞
            log.warn("風險評分拒絕資格 userId={} activityId={} score={} reasons={}",
                    user.id(), activity.id(), assessment.score(), assessment.reasons());
            throw new BusinessException(ErrorCode.RISK_REJECTED);
        }

        // 憑證活不過活動結束：活動結束後它就沒有意義，也不該留著給下一檔用
        Instant expiresAt = earliest(now.plus(settings.tokenTtl()), activity.period().endAt());
        QualificationToken token = new QualificationToken(
                user.id(), activity.id(), expiresAt, UUID.randomUUID().toString());
        return new Qualification(tokenCodec.issue(token), expiresAt);
    }

    /** 開賣前 leadTime 內可領；結束後不可領。太早領只會讓憑證在開賣前就過期。 */
    private void ensureWithinQualificationWindow(SeckillActivity activity, Instant now) {
        if (activity.period().endedAt(now)) {
            throw new BusinessException(ErrorCode.ACTIVITY_ENDED);
        }
        Instant opensAt = activity.period().startAt().minus(settings.leadTime());
        if (now.isBefore(opensAt)) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_STARTED,
                    "開賣前 " + settings.leadTime().toMinutes() + " 分鐘才能領取搶購資格");
        }
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
