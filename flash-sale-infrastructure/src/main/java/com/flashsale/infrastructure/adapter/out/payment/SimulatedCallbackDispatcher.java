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

/** 模擬金流閘道的非同步回調。 */
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

    /** 在付款發起的交易<b>提交之後</b>才排程回調。 */
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
