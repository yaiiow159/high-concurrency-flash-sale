package com.flashsale.application.service;

import com.flashsale.application.port.in.RefundExecutionUseCase;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.application.port.out.PaymentRepository;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.payment.Payment;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 執行退款——退款 Saga 的慢車道（ADR-0011 決策 8）。
 *
 * <h2>順序：先退錢，再退庫存</h2>
 *
 * <p>與 ADR-0008「先 MySQL 再 Redis」同一個判準——<b>看失敗時往哪邊倒</b>：
 *
 * <ul>
 *   <li>先退庫存後退錢失敗 → 貨回到可售池但客人沒拿到錢。
 *       貨其實還在客人手上，等於<b>超賣</b>，而且客人一定會客訴</li>
 *   <li>先退錢後退庫存失敗 → 客人拿到錢但貨沒回可售池，
 *       是<b>少賣</b>；對帳看得到，補得回來</li>
 * </ul>
 *
 * <h2>兩層冪等，各擋不同的東西</h2>
 *
 * <p>金流那層靠冪等鍵（退貨單號）由閘道認出重試——
 * 「請求已送達但回應遺失」這個狀態只有對方知道，我們這邊無從判斷。
 * 庫存那層靠庫存流水的唯一鍵，來源記的是<b>退貨單號而非訂單號</b>，
 * 因為一張訂單可以有多張退貨單。
 */
@Service
public class RefundExecutionService implements RefundExecutionUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundExecutionService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final InventoryService inventoryService;
    private final PaymentMetrics metrics;

    public RefundExecutionService(PaymentRepository paymentRepository,
                                  PaymentGateway paymentGateway,
                                  InventoryService inventoryService,
                                  PaymentMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.inventoryService = inventoryService;
        this.metrics = metrics;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>刻意不加 {@code @Transactional}。</b>這裡有一次遠端金流呼叫，
     * 把它包在交易裡會讓資料庫交易的存活時間綁在閘道的回應時間上——
     * 尖峰時那是連線池耗盡的標準劇本。
     * 庫存回補自己有交易邊界，退款結果由閘道的冪等鍵保護。
     */
    @Override
    public void execute(RefundRequestedEvent event) {
        Payment payment = paymentRepository.findByOrderNo(OrderNo.of(event.orderNo()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "訂單 %s 沒有付款紀錄".formatted(event.orderNo())));

        // 冪等鍵用退貨單號：同一張退貨單重投幾次，閘道都認得是同一筆
        PaymentGateway.RefundOutcome outcome =
                paymentGateway.refund(payment, event.refundAmount(), event.returnNo());
        if (!outcome.succeeded()) {
            metrics.recordRefund(false);
            // 必須是**可重試**的錯誤碼（C 系列）。退款沒有補償動作可做——
            // 「已核可的退款」只能往前推到成功，不能回頭當作沒發生。
            //
            // 先前這裡丟 IllegalStateException，而那個型別在 KafkaConsumerConfig
            // 被歸為不可重試，於是閘道一次暫時性故障就讓訊息直接進死信，
            // 但付款紀錄早已 commit 成「已退」——帳上退了、錢沒退
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "退款失敗 returnNo=%s, 原因=%s".formatted(event.returnNo(), outcome.failureReason()));
        }
        log.info("已退款 returnNo={}, 金額={}, 閘道編號={}",
                event.returnNo(), event.refundAmount(), outcome.gatewayReference());

        restock(event);
        // 計數放在庫存回補之後：擺在前面的話，回補失敗被重投時會重複計數，
        // 而那個指標正是用來看「退款成功了幾筆」的
        metrics.recordRefund(true);
    }

    /**
     * 回補庫存。
     *
     * <p><b>一律回一般庫存，即使原本是秒殺訂單</b>（ADR-0011 決策 4）。
     * 活動可能早就結束並釋放過額度，把量寫回 Redis 等於復活一個已釋放的活動；
     * 而 Redis 的庫存鍵有 TTL，寫進一個會過期的鍵，那批貨會安靜消失。
     *
     * <p>不可再售的品項不在事件裡——它們在驗收時就被濾掉了，
     * 而且<b>不補任何庫存流水</b>：原本的 DEDUCT 已經記過那批貨離開，
     * 報廢只是它真的沒回來。
     */
    private void restock(RefundRequestedEvent event) {
        for (RefundRequestedEvent.RestockLine line : event.restockLines()) {
            boolean restored = inventoryService.restore(InventoryService.RestoreCommand.forReturn(
                    line.skuId(), event.userId(), line.quantity(),
                    event.orderNo(), event.returnNo()));
            if (!restored) {
                log.debug("退貨單 {} 的 SKU {} 已回補過，略過", event.returnNo(), line.skuId());
            }
        }
    }
}
