package com.flashsale.application.service;

import com.flashsale.application.port.out.PaymentMetrics;
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

/** 執行退款——退款 Saga 的慢車道（ADR-0011 決策 8）。 */
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

    /** {@inheritDoc} */
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

    /** 回補庫存。 */
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
