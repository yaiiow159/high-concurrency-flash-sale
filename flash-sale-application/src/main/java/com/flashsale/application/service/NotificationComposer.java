package com.flashsale.application.service;

import com.flashsale.domain.notification.NotificationType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 把事件變成使用者讀得懂的文字。
 *
 * <h2>為什麼在應用層而不是領域層</h2>
 *
 * <p>這裡放的是<b>文案</b>——面向使用者的措辭，會因為行銷、法務或
 * 客服回饋而反覆調整。它不是業務規則，領域層不該因為改一句話而變動。
 *
 * <p>領域層拿到的是<b>算好的結果</b>（{@code title} / {@code body}），
 * 並把它當快照存下來。這個分工讓「文案可以改」與
 * 「已寄出的通知不可變」兩件事同時成立。
 *
 * <h2>文案的三條規則</h2>
 *
 * <ol>
 *   <li><b>講使用者接下來會遇到什麼，不講系統做了什麼。</b>
 *       「訂單狀態已更新為 SHIPPED」對使用者毫無資訊；
 *       「已出貨，可以開始追蹤物流」才回答了他真正在問的事</li>
 *   <li><b>金額一律帶單位並加千分位。</b>「已收到 101700」會被誤讀，
 *       這是實際會造成客訴的細節</li>
 *   <li><b>不放連結。</b>站內信的介面自己知道要連去哪（{@code referenceNo}），
 *       Email 的網址在不同環境不一樣——把它寫進快照，
 *       正式環境就會寄出指向 localhost 的信</li>
 * </ol>
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
        };
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0" : MONEY.format(amount);
    }

    /** 算好的通知文字。交給聚合根之後就是快照，不再重算。 */
    public record Content(String title, String body) {
    }
}
