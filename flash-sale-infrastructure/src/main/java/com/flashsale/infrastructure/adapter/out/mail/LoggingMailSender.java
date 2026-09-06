package com.flashsale.infrastructure.adapter.out.mail;

import com.flashsale.application.port.out.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/** 把信寫進日誌的模擬寄件者。 */
@Component
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    /** 只做最基本的形狀檢查。 */
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
