package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Size;

/**
 * 審核退貨的請求體。
 *
 * <p>{@code note} 在核准時可選、駁回時必填——那條規則守在聚合根裡而不是這裡，
 * 因為它是業務規則不是輸入格式。駁回而不說原因會直接變成客訴。
 */
public record ReturnReviewRequest(

        @Size(max = 512, message = "審核說明不可超過 512 字")
        String note
) {
}
