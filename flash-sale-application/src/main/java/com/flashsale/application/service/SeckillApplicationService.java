package com.flashsale.application.service;

import com.flashsale.application.port.in.SeckillUseCase;
import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;
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
import java.time.Instant;

/**
 * 搶購主流程。
 *
 * <p><b>刻意沒有 {@code @Transactional}</b>：這條鏈路上沒有任何資料庫寫入。
 * 這正是削峰的重點——把最慢、最容易成為瓶頸的 DB 寫入完全移出請求路徑。
 *
 * <p>請求會依序穿過四層漏斗，每一層都比下一層便宜好幾個數量級：
 * <ol>
 *   <li><b>本機售罄標記</b>（奈秒級）— 擋掉售罄後湧入的絕大多數請求</li>
 *   <li><b>多級快取讀活動</b>（Caffeine 奈秒 / Redis 微秒）— 業務規則校驗，不打 DB</li>
 *   <li><b>Redis Lua 原子扣減</b>（單次 RTT）— 唯一的強一致點，防超賣的核心</li>
 *   <li><b>MQ 投遞</b>（單次 RTT）— 把建單交給消費端慢慢做</li>
 * </ol>
 *
 * <p><b>失敗補償</b>：第 3 步成功但第 4 步失敗時，必須立刻退回庫存，
 * 否則就是「庫存扣了但訂單永遠不會出現」的少賣。
 */
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
    private final Clock clock;

    public SeckillApplicationService(ActivityRepository activityRepository,
                                     StockRepository stockRepository,
                                     SeckillMessagePublisher messagePublisher,
                                     SeckillRequestTracker requestTracker,
                                     SoldOutMarker soldOutMarker,
                                     OrderQueueDepth queueDepth,
                                     OrderNoGenerator orderNoGenerator,
                                     SeckillMetrics metrics,
                                     Clock clock) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.messagePublisher = messagePublisher;
        this.requestTracker = requestTracker;
        this.soldOutMarker = soldOutMarker;
        this.queueDepth = queueDepth;
        this.orderNoGenerator = orderNoGenerator;
        this.metrics = metrics;
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
        }
    }

    private SeckillTicket execute(SeckillCommand command) {
        rejectIfSoldOutLocally(command.activityId());
        rejectIfQueueOverloaded();

        SeckillActivity activity = loadPurchasableActivity(command);
        OrderNo candidateOrderNo = orderNoGenerator.next();
        StockDeductionResult deduction = deductStock(command, activity, candidateOrderNo);

        // 重送同一個 requestId：回放首次扣減時綁定的訂單號，使用者看到的是同一張訂單。
        if (deduction.isDuplicate()) {
            log.debug("重複的搶購請求 requestId={}, 回放既有訂單 {}", command.requestId(), deduction.orderNo());
            return SeckillTicket.accepted(deduction.orderNo());
        }

        return publishOrCompensate(command, OrderNo.of(deduction.orderNo()));
    }

    /**
     * 第 1 層漏斗：本機售罄標記。
     *
     * <p>庫存 1000 件卻湧入百萬請求時，售罄後的請求佔絕大多數。
     * 在這裡以一次記憶體讀取擋下，Redis 才不會被必敗的請求打垮。
     */
    private void rejectIfSoldOutLocally(Long activityId) {
        if (soldOutMarker.isSoldOut(activityId)) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
    }

    /**
     * 第 1.5 層漏斗：入場控制（ADR-0023）。
     *
     * <p>庫存還有，但建單佇列已經積到使用者要等太久——此時繼續收單
     * 只是把等待時間變得更長。<b>擋在扣庫存之前</b>，
     * 被擋下的請求沒有扣到庫存，不需要補償，也不會產生孤兒扣減。
     *
     * <p><b>讀的是記憶體裡的快取值，不問 Kafka。</b>
     * 熱路徑上只有 Redis 與 Kafka 各一次，那是上限——
     * 這一層與售罄標記同型，都是用一次記憶體讀取擋下不該進來的請求。
     *
     * <p>已經扣了庫存的請求一律不受影響：承諾已經做出去了，
     * 反悔等於少賣，而且是系統自己造成的。
     */
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
     * 第 4 層漏斗：投遞建單訊息。失敗則立刻補償退庫。
     *
     * <p>先 {@code markAccepted} 再投遞，順序不可顛倒：若先投遞成功、標記卻失敗，
     * 前端輪詢會在訂單落庫前拿到 404，體驗上等同搶購失敗。
     */
    private SeckillTicket publishOrCompensate(SeckillCommand command, OrderNo orderNo) {
        try {
            requestTracker.markAccepted(orderNo.value(), command.userId());
            messagePublisher.publish(SeckillOrderMessage.of(orderNo.value(), command, clock.instant()));
            return SeckillTicket.accepted(orderNo.value());
        } catch (RuntimeException e) {
            compensateStock(command, orderNo, e);
            throw new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED,
                    "訂單受理失敗，庫存已退回，請重新嘗試", e);
        }
    }

    /**
     * 補償退庫。
     *
     * <p>補償本身失敗時<b>只記錄不拋出</b>——若讓補償的例外覆蓋原始錯誤，
     * 排查時會完全看不到真正的故障點。這筆未退回的庫存交由對帳排程收尾，
     * 並透過 {@code seckill.compensation.total{result="failure"}} 指標告警。
     */
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
