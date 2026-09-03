package com.flashsale.application.port.out;

/**
 * 郵件寄送埠（出站）。
 *
 * <p><b>介面刻意不回傳「已送達」，只回傳「已交付給郵件伺服器」。</b>
 * 那是這條路徑上唯一能誠實斷言的事——信是否真的進了收件匣、
 * 有沒有被歸進垃圾郵件、使用者有沒有讀，我們都無從得知。
 * 若介面設計成回傳 {@code delivered}，呼叫端遲早會拿它當「使用者已經知道了」，
 * 而那個假設會在客服現場破功。
 *
 * <p>與 {@link PaymentGateway} 同樣的取捨：模擬實作要模擬真實系統的行為，
 * 而不是因為是模擬就走捷徑。
 */
public interface MailSender {

    /**
     * 交付一封信給郵件伺服器。
     *
     * @return 成功時帶郵件伺服器的訊息 ID，失敗時帶原因
     */
    Outcome send(String recipient, String subject, String body);

    /**
     * @param retryable 失敗是否值得重試。SMTP 暫時故障值得；
     *                  信箱格式錯誤重試一萬次都是同樣結果，
     *                  只會讓排程每輪都白跑一次
     */
    record Outcome(boolean succeeded, String messageId, String failureReason, boolean retryable) {

        public static Outcome success(String messageId) {
            return new Outcome(true, messageId, null, false);
        }

        /** 暫時性故障，下一輪再試。 */
        public static Outcome transientFailure(String reason) {
            return new Outcome(false, null, reason, true);
        }

        /** 永久性失敗（例如信箱不存在），不再重試。 */
        public static Outcome permanentFailure(String reason) {
            return new Outcome(false, null, reason, false);
        }
    }
}
