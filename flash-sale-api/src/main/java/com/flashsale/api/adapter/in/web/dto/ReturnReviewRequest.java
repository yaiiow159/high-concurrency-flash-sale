package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.Size;

/** 審核退貨的請求體。 */
public record ReturnReviewRequest(

        @Size(max = 512, message = "審核說明不可超過 512 字")
        String note
) {
}
