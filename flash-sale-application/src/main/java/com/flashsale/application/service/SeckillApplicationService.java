package com.flashsale.application.service;

import com.flashsale.application.port.out.SeckillMetrics;
import com.flashsale.application.port.in.SeckillUseCase;
import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;
import com.flashsale.application.config.QualificationSettings;
import com.flashsale.application.port.out.QualificationTokenCodec;
import com.flashsale.domain.risk.QualificationToken;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderQueueDepth;
import com.flashsale.application.port.out.SeckillMessagePublisher;
import com.flashsale.application.port.out.SeckillRequestTracker;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Locale;
import java.time.Instant;

/** 搶購主流程。 */
@Service
public class SeckillApplicationService implements SeckillUseCase {

    private static final Logger log = LoggerFactory.getLogger(SeckillApplicationService.class);

    private final ActivityRepository activityRepository;
    private final StockRepository stockRepository;
    private final SeckillMessagePublisher messagePublisher;
    private final SeckillRequestTracker requestTracker;
    private final SoldOutMarker soldOutMarker;
    private final OrderQueueDepth queueDepth;
    private final OrderNoGenerator orderNoGenerator;
    private final SeckillMetrics metrics;
    private final QualificationTokenCodec qualificationCodec;
    private final QualificationSettings qualificationSettings;
    private final Clock clock;

    public SeckillApplicationService(ActivityRepository activityRepository,
                                     StockRepository stockRepository,
                                     SeckillMessagePublisher messagePublisher,
                                     SeckillRequestTracker requestTracker,
                                     SoldOutMarker soldOutMarker,
                                     OrderQueueDepth queueDepth,
                                     OrderNoGenerator orderNoGenerator,
                                     SeckillMetrics metrics,
                                     QualificationTokenCodec qualificationCodec,
                                     QualificationSettings qualificationSettings,
                                     Clock clock) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.messagePublisher = messagePublisher;
        this.requestTracker = requestTracker;
        this.soldOutMarker = soldOutMarker;
        this.queueDepth = queueDepth;
        this.orderNoGenerator = orderNoGenerator;
        this.metrics = metrics;
        this.qualificationCodec = qualificationCodec;
        this.qualificationSettings = qualificationSettings;
        this.clock = clock;
    }

    @Override
    public SeckillTicket attempt(SeckillCommand command) {
        long startNanos = System.nanoTime();
        try {
            SeckillTicket ticket = execute(command);
            metrics.recordSuccess(command.activityId(), startNanos);
            return ticket;
        } catch (BusinessException e) {
            metrics.recordRejection(command.activityId(), e.errorCode(), startNanos);
            throw e;
        } catch (RuntimeException e) {
            // 「賣完了」與「壞掉了」要分得開：少了這一段，Redis 掛掉時儀表板上
            // 只看得到 QPS 掉下去，沒有任何錯誤指標會上升
            metrics.recordError(command.activityId(), startNanos);
            throw e;
        }
    }

    private SeckillTicket execute(SeckillCommand command) {
        rejectIfSoldOutLocally(command.activityId());
        rejectIfQueueOverloaded();
        verifyQualification(command);

        SeckillActivity activity = loadPurchasableActivity(command);
        OrderNo candidateOrderNo = orderNoGenerator.next();
        StockDeductionResult deduction = deductStock(command, activity, candidateOrderNo);

        // 重送同一個 requestId：回放首次扣減時綁定的訂單號，使用者看到的是同一張訂單。
        if (deduction.isDuplicate()) {
            log.debug("重複的搶購請求 requestId={}, 回放既有訂單 {}", command.requestId(), deduction.orderNo());
            return republish(command, OrderNo.of(deduction.orderNo()));
        }

        return publishOrCompensate(command, OrderNo.of(deduction.orderNo()));
    }

    /** 第 1 層漏斗：本機售罄標記。 */
    /**
     * 資格憑證：純 CPU 驗簽，零遠端呼叫——這是它能待在熱路徑上的唯一理由。
     * 身分、黑名單、風險評分都在領憑證時（冷路徑）做完了，這裡只認簽章與到期。
     */
    private void verifyQualification(SeckillCommand command) {
        if (!qualificationSettings.requireQualification()) {
            return;
        }
        if (command.qualificationToken() == null || command.qualificationToken().isBlank()) {
            throw new BusinessException(ErrorCode.QUALIFICATION_REQUIRED);
        }
        QualificationToken token = qualificationCodec.verify(command.qualificationToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.QUALIFICATION_INVALID));
        token.ensureUsableBy(command.userId(), command.activityId(), clock.instant());
    }

    private void rejectIfSoldOutLocally(Long activityId) {
        if (soldOutMarker.isSoldOut(activityId)) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
    }

    /** 第 1.5 層漏斗：入場控制（ADR-0023）。 */
    private void rejectIfQueueOverloaded() {
        if (queueDepth.isOverloaded()) {
            throw new BusinessException(ErrorCode.QUEUE_OVERLOADED);
        }
    }

    /** 第 2 層漏斗：多級快取讀活動並校驗業務規則。快取實作細節由基礎設施層的 Decorator 承擔。 */
    private SeckillActivity loadPurchasableActivity(SeckillCommand command) {
        Instant now = clock.instant();
        SeckillActivity activity = activityRepository.findById(command.activityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND));
        activity.ensurePurchasableAt(now);
        activity.ensureQuantityWithinLimit(command.quantity());
        return activity;
    }

    /** 第 3 層漏斗：Redis Lua 原子扣減——全系統唯一的強一致點。 */
    private StockDeductionResult deductStock(SeckillCommand command, SeckillActivity activity, OrderNo orderNo) {
        StockDeductionResult result = stockRepository.deduct(
                command.activityId(),
                command.userId(),
                command.quantity(),
                activity.perUserLimit(),
                command.requestId(),
                orderNo.value());

        if (result.outcome() == StockDeductionOutcome.SOLD_OUT) {
            // 立刻豎起本機標記，讓後續請求走第 1 層漏斗，不再打 Redis。
            soldOutMarker.markSoldOut(command.activityId());
        }
        if (!result.holdsStock()) {
            throw result.outcome().toException();
        }
        return result;
    }

    /**
     * 第 4 層漏斗：投遞建單訊息。
     *
     * <p><b>只有「確定沒送出」才補償退庫。</b>等待逾時的語意是「不知道送到沒」——
     * 生產者仍在重試，訊息很可能之後才送達；此時退庫會讓那份庫存被別人買走，
     * 而訂單稍後照樣建立，也就是真實超賣。逾時一律照常受理，讓前端輪詢；
     * 訊息最終真的沒送達時，那筆扣減會被對帳的孤兒偵測撈出來（ADR-0030）。
     */
    private SeckillTicket publishOrCompensate(SeckillCommand command, OrderNo orderNo) {
        SeckillMessagePublisher.Outcome outcome;
        try {
            requestTracker.markAccepted(orderNo.value(), command.userId());
            outcome = messagePublisher.publish(
                    SeckillOrderMessage.of(orderNo.value(), command, clock.instant()));
        } catch (RuntimeException e) {
            // 補償是這個 catch 的第一件事：任何東西都不可以有能力阻止退庫
            compensateStock(command, orderNo, e);
            recordPublishQuietly(command.activityId(), "failed");
            throw new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED,
                    "訂單受理失敗，庫存已退回，請重新嘗試", e);
        }
        // **記指標在 try 之外。** 放在裡面的話，一個 Micrometer 的例外會讓
        // 已經 ACK（訂單一定會建）的請求落進 catch 而被退庫——那就是超賣
        recordPublishQuietly(command.activityId(), outcome.name().toLowerCase(Locale.ROOT));
        return SeckillTicket.accepted(orderNo.value());
    }

    /** 遙測不可以改變業務結果，因此它自己的例外一律吞掉。 */
    private void recordPublishQuietly(Long activityId, String outcome) {
        try {
            metrics.recordPublish(activityId, outcome);
        } catch (RuntimeException e) {
            log.warn("記錄投遞指標失敗 activityId={}, outcome={}", activityId, outcome, e);
        }
    }

    /**
     * 重複請求：回放同一個訂單號，並且**再投遞一次**同樣的訊息。
     *
     * <p>不重投的話會有一個卡死：首次請求若走了 PENDING 而訊息最終遺失，
     * 憑證會留著，於是重送永遠命中冪等分支、永遠不會有訊息被投出去，
     * 訂單就停在「處理中」直到追蹤鍵過期——使用者重試幾次都一樣。
     *
     * <p>重投的安全性由既有的三層冪等承擔（Redis 已回重複不會再扣、
     * 消費端 saveIfAbsent、request_id 唯一索引），代價只是一則多餘的訊息。
     *
     * <p><b>這條路徑絕不補償。</b>重複代表憑證還在，而首次請求可能已經建好單了；
     * 此時退庫會把一份已經賣掉的量放回可售池。投遞失敗就讓它失敗，
     * 剩下的交給對帳的孤兒偵測。
     */
    private SeckillTicket republish(SeckillCommand command, OrderNo orderNo) {
        try {
            SeckillMessagePublisher.Outcome outcome = messagePublisher.publish(
                    SeckillOrderMessage.of(orderNo.value(), command, clock.instant()));
            recordPublishQuietly(command.activityId(), "duplicate-" + outcome.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            log.warn("重複請求的訊息重投失敗，不補償 orderNo={}, requestId={}",
                    orderNo, command.requestId(), e);
            recordPublishQuietly(command.activityId(), "duplicate-failed");
        }
        return SeckillTicket.accepted(orderNo.value());
    }

    /** 補償退庫。 */
    private void compensateStock(SeckillCommand command, OrderNo orderNo, RuntimeException cause) {
        log.error("訊息投遞失敗，開始補償退庫 orderNo={}, requestId={}", orderNo, command.requestId(), cause);
        boolean restored = false;
        try {
            restored = stockRepository.restore(
                    command.activityId(), command.userId(), command.quantity(), command.requestId());
            if (restored) {
                soldOutMarker.clear(command.activityId());
            }
            requestTracker.markFailed(orderNo.value(), "訂單受理失敗，庫存已退回");
        } catch (RuntimeException compensationFailure) {
            log.error("庫存補償失敗，需人工介入 orderNo={}, requestId={}",
                    orderNo, command.requestId(), compensationFailure);
        } finally {
            metrics.recordCompensation(command.activityId(), "publish-failure", restored);
        }
    }
}
