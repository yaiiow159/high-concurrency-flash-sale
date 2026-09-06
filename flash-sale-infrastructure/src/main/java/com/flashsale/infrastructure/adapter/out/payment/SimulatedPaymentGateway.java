package com.flashsale.infrastructure.adapter.out.payment;

import com.flashsale.application.port.out.PaymentGateway;
import com.flashsale.domain.payment.Payment;
import com.flashsale.infrastructure.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 模擬金流閘道。 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private static final String SIGNATURE_PARAM = "signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 冪等鍵 → 退款結果。模擬真實閘道辨識重試的能力。 */
    private final Map<String, RefundOutcome> refundsByKey = new ConcurrentHashMap<>();

    private final PaymentProperties properties;

    public SimulatedPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
        log.warn("""

                ============================================================
                  使用模擬金流閘道，不會發生真實金流。
                  接上真實金流請替換 SimulatedPaymentGateway。
                ============================================================""");
    }

    @Override
    public PaymentIntent initiate(Payment payment) {
        String gatewayReference = "SIM-" + UUID.randomUUID().toString().replace("-", "");
        String paymentUrl = "%s?paymentNo=%s&amount=%s"
                .formatted(properties.simulatedCheckoutUrl(), payment.paymentNo().value(), payment.amount());
        log.info("模擬閘道已建立付款 paymentNo={}, ref={}", payment.paymentNo(), gatewayReference);
        return new PaymentIntent(gatewayReference, paymentUrl);
    }

    /** {@inheritDoc} */
    @Override
    public boolean verifyCallbackSignature(Map<String, String> parameters) {
        String received = parameters.get(SIGNATURE_PARAM);
        if (received == null || received.isBlank()) {
            return false;
        }
        return constantTimeEquals(received, sign(parameters));
    }

    /** 為參數簽章。回調模擬器也用這個方法，確保簽與驗用的是同一份邏輯—— 兩邊各寫一份，遲早會不一致。 */
    public String sign(Map<String, String> parameters) {
        Map<String, String> sorted = new TreeMap<>(parameters);
        sorted.remove(SIGNATURE_PARAM);

        StringBuilder payload = new StringBuilder();
        sorted.forEach((key, value) -> payload.append(key).append('=').append(value).append('&'));

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.callbackSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("計算回調簽章失敗，請檢查金流金鑰設定", e);
        }
    }

    /** 常數時間比對。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 模擬退款。 */
    @Override
    public RefundOutcome refund(Payment payment, BigDecimal amount, String idempotencyKey) {
        RefundOutcome existing = refundsByKey.get(idempotencyKey);
        if (existing != null) {
            log.info("模擬閘道辨識出重複退款請求，回傳原結果 key={}, ref={}",
                    idempotencyKey, existing.gatewayReference());
            return existing;
        }
        log.info("模擬閘道執行退款 paymentNo={}, 金額={}, key={}",
                payment.paymentNo(), amount, idempotencyKey);
        RefundOutcome outcome = RefundOutcome.success(
                "SIM-REFUND-" + UUID.randomUUID().toString().replace("-", ""));
        RefundOutcome raced = refundsByKey.putIfAbsent(idempotencyKey, outcome);
        return raced == null ? outcome : raced;
    }
}
