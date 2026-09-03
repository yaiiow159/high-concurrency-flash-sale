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

/**
 * 模擬金流閘道。
 *
 * <p><b>它模擬的是真實金流的「流程」，而不是走捷徑。</b>
 * 真實金流一律是非同步的：發起 → 使用者在閘道頁面操作 → 閘道回調通知結果。
 * 若因為是模擬就設計成同步回傳，之後接真實金流時整條鏈路都要重寫。
 *
 * <p>簽章邏輯是<b>真的</b>：參數按鍵排序後串接、HMAC-SHA256、常數時間比對。
 * 這一段與接綠界或 Stripe 時的作法一致，換閘道只需改欄位名與雜湊演算法。
 *
 * <p>接真實金流時（P3）替換這個類別即可，{@code PaymentGateway} 介面不變。
 */
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

    /**
     * {@inheritDoc}
     *
     * <p>驗簽步驟與真實閘道相同：
     * <ol>
     *   <li>取出簽章欄位並從待簽參數中排除</li>
     *   <li>其餘參數<b>按鍵排序</b>後串接——排序是必要的，
     *       HTTP 參數的順序不保證，不排序會導致簽章隨機失敗</li>
     *   <li>HMAC-SHA256 後與收到的簽章比對</li>
     * </ol>
     */
    @Override
    public boolean verifyCallbackSignature(Map<String, String> parameters) {
        String received = parameters.get(SIGNATURE_PARAM);
        if (received == null || received.isBlank()) {
            return false;
        }
        return constantTimeEquals(received, sign(parameters));
    }

    /**
     * 為參數簽章。回調模擬器也用這個方法，確保簽與驗用的是同一份邏輯——
     * 兩邊各寫一份，遲早會不一致。
     */
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

    /**
     * 常數時間比對。
     *
     * <p>用 {@code equals} 會在第一個不同的位元組就返回，
     * 攻擊者能藉由回應時間逐位元組試出正確簽章。
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 模擬退款。
     *
     * <p><b>模擬的是「真實閘道會怎麼做」，而不是「因為是模擬所以走捷徑」。</b>
     * 真實閘道用冪等鍵認出重試並回傳<b>原本那筆</b>的結果，
     * 這裡用一個記憶體 Map 做同一件事——少了它，
     * 消費端重投時會拿到一個新的退款編號，測試就永遠看不到重複退款的問題。
     *
     * <p>記憶體 Map 在多節點下當然不成立，但那正是真實閘道存在的理由：
     * 冪等要在收錢的那一端保證，不是在我們這端。
     */
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
