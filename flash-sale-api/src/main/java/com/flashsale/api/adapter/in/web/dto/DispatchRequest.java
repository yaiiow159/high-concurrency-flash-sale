package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.fulfillment.Carrier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 出貨請求體。
 *
 * <p>承運商用列舉而非自由字串：查詢網址依承運商而異，
 * 自由字串會讓「黑貓」「黑貓宅急便」「TCAT」變成三個承運商，
 * 而查詢連結只能對其中一個生效。
 */
public record DispatchRequest(

        @NotNull(message = "請指定承運商")
        Carrier carrier,

        /** 沒有單號的出貨等於無法追蹤，使用者問「東西到哪了」時只能回答不知道。 */
        @NotBlank(message = "物流單號不可為空")
        @Size(max = 64, message = "物流單號不可超過 64 字")
        String trackingNumber
) {
}
