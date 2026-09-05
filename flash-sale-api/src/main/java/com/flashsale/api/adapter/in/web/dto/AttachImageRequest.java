package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** 上傳完成後把物件掛到商品上。 */
public record AttachImageRequest(

        @NotBlank(message = "objectKey 不可為空")
        String objectKey,

        @NotBlank(message = "contentType 不可為空")
        String contentType,

        @Positive(message = "檔案大小必須大於 0")
        long byteSize
) {
}
