package com.flashsale.application.service;

import com.flashsale.domain.notification.NotificationType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 把<b>訂單事件</b>變成使用者讀得懂的文字。
 *
 * <p>刻意用窮盡的 switch：新增通知類型時這裡會編譯失敗，
 * 逼人回答「這一種要說什麼」，而不是安靜地寄出一封空白的信。
 */
@Component
public class NotificationComposer {

    /** 台幣沒有小數，顯示到分只會讓數字更難讀。 */
    private static final NumberFormat MONEY = NumberFormat.getNumberInstance(Locale.TAIWAN);

    static {
        MONEY.setMaximumFractionDigits(0);
    }

    public Content compose(NotificationType type, String referenceNo, BigDecimal amount) {
        return switch (type) {
            case ORDER_PAID -> new Content(
                    "付款成功",
                    "訂單 %s 已收到款項 NT$ %s，我們正在為你準備出貨。"
                            .formatted(referenceNo, money(amount)));
            case ORDER_SHIPPED -> new Content(
                    "商品已出貨",
                    "訂單 %s 已交給物流，可以到訂單頁查看配送進度。".formatted(referenceNo));
            case ORDER_COMPLETED -> new Content(
                    "商品已送達",
                    // 明講鑑賞期從送達起算：那是使用者最常問、也最容易誤會的一件事
                    "訂單 %s 已送達。若需要退貨，可從訂單頁提出申請。".formatted(referenceNo));
            case ORDER_CANCELLED -> new Content(
                    "訂單已取消",
                    "訂單 %s 已取消，若已扣款將自動退回。".formatted(referenceNo));
            case REFUND_SENT -> new Content(
                    "退款已送出",
                    // 不承諾具體天數：那取決於發卡行，寫死會變成做不到的承諾
                    "退貨單 %s 的退款 NT$ %s 已送出，將依原付款方式退回。"
                            .formatted(referenceNo, money(amount)));
            // 到貨通知不經過這裡：它要帶商品名稱，而這個方法只拿得到單號與金額。
            // 文字在 RestockNotificationService 組。留這個 case 是為了讓
            // switch 保持窮盡——下一個新增的類型仍然會在這裡編譯失敗
            case RESTOCKED -> throw new IllegalArgumentException(
                    "到貨通知的文字由 RestockNotificationService 組，不走這裡");
        };
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0" : MONEY.format(amount);
    }

    /** 算好的通知文字。交給聚合根之後就是快照，不再重算。 */
    public record Content(String title, String body) {
    }
}
