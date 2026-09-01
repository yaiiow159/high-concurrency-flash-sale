package com.flashsale.infrastructure.adapter.out.payment;

import com.flashsale.application.port.in.PaymentUseCase;
import com.flashsale.domain.payment.event.PaymentInitiatedSignal;
import com.flashsale.infrastructure.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 模擬金流閘道的非同步回調。
 *
 * <p><b>這個類別代表真實世界中「不在我們掌控內」的那一半</b>：
 * 使用者在閘道頁面完成操作後，閘道<b>主動打回來</b>通知結果。
 *
 * <p>刻意做成非同步而非在發起時同步完成，理由是要讓
 * 「回調可能比前端更早或更晚到」「回調可能重送」「回調期間訂單可能已被關閉」
 * 這些真實情境都能在本機重現。若模擬時走同步捷徑，
 * 那些情境只會在接上真實金流後才第一次出現——而那是最糟的發現時機。
 *
 * <p>簽章由 {@link SimulatedPaymentGateway#sign} 產生，與驗簽共用同一份邏輯；
 * 兩邊各寫一份，遲早會不一致。
 */
@Component
public class SimulatedCallbackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCallbackDispatcher.class);

    private final PaymentUseCase paymentUseCase;
    private final SimulatedPaymentGateway gateway;
    private final PaymentProperties properties;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public SimulatedCallbackDispatcher(PaymentUseCase paymentUseCase,
                                       SimulatedPaymentGateway gateway,
                                       PaymentProperties properties,
                                       TaskScheduler taskScheduler,
                                       Clock clock) {
        this.paymentUseCase = paymentUseCase;
        this.gateway = gateway;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    /**
     * 在付款發起的交易<b>提交之後</b>才排程回調。
     *
     * <p>順序是關鍵：若在交易提交前就送出回調，回調處理會查不到那張付款單
     * （它還沒 commit），變成偶發的「付款單不存在」。
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 保證這件事。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentInitiated(PaymentInitiatedSignal signal) {
        Instant fireAt = clock.instant().plus(properties.simulateCallbackDelay());
        taskScheduler.schedule(() -> dispatch(signal.paymentNo()), fireAt);
        log.debug("已排程模擬回調 paymentNo={}, 延遲={}", signal.paymentNo(), properties.simulateCallbackDelay());
    }

    private void dispatch(String paymentNo) {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("paymentNo", paymentNo);
        parameters.put("result", properties.autoSucceed() ? "SUCCESS" : "FAILED");
        parameters.put("transactionId", "SIM-TXN-" + UUID.randomUUID().toString().replace("-", ""));
        if (!properties.autoSucceed()) {
            parameters.put("failureReason", "模擬付款失敗");
        }
        parameters.put("signature", gateway.sign(parameters));

        try {
            paymentUseCase.handleGatewayCallback(parameters);
        } catch (RuntimeException e) {
            // 真實閘道遇到錯誤會重送；模擬環境只記錄，避免無限重試干擾開發
            log.warn("模擬回調處理失敗 paymentNo={}（真實閘道此時會重送）", paymentNo, e);
        }
    }
}
