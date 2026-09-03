package com.flashsale.infrastructure.adapter.out.mail;

import com.flashsale.application.port.out.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 把信寫進日誌的模擬寄件者。
 *
 * <p>沒有接真實 SMTP 是刻意的：那需要憑證與一個真的能收信的網域，
 * 而這個專案要展示的是<b>通知的可靠性設計</b>——冪等、重試分類、
 * 快照內容、失敗可追查——那些與真的把 TCP 連到 587 埠無關。
 *
 * <p>但它<b>模擬真實寄件者會有的行為</b>，而不是因為是模擬就一律回成功：
 *
 * <ul>
 *   <li>信箱格式不合法回<b>永久性失敗</b>——真實 SMTP 會回 5xx，重試一萬次都一樣。
 *       這一條讓 {@code UNDELIVERABLE} 那條路徑真的會被走到，
 *       而不是一段永遠測不到的程式碼</li>
 *   <li>回傳訊息 ID，因為真實寄件者會給，而那是事後追查唯一的線索</li>
 * </ul>
 *
 * <p>換成真實實作時只要替換這個類別，上層完全不動——那是 Port 的價值。
 */
@Component
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    /**
     * 只做最基本的形狀檢查。
     *
     * <p>刻意不用「完整」的 RFC 5322 正規表示式：那種表達式長達數百字元、
     * 沒有人讀得懂，而且仍然會拒絕合法的信箱。
     * 真正的驗證只能靠寄出去看對方收不收。
     */
    private static final Pattern PLAUSIBLE_EMAIL =
            Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    @Override
    public Outcome send(String recipient, String subject, String body) {
        if (recipient == null || !PLAUSIBLE_EMAIL.matcher(recipient).matches()) {
            return Outcome.permanentFailure("信箱格式不合法: " + recipient);
        }

        String messageId = "SIM-MAIL-" + UUID.randomUUID().toString().replace("-", "");
        log.info("[模擬寄信] to={}, subject={}, messageId={}\n{}",
                recipient, subject, messageId, body);
        return Outcome.success(messageId);
    }
}
