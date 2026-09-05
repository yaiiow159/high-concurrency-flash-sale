package com.flashsale.api.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/**
 * 要一張上傳授權。
 *
 * @param sha256 檔案內容的雜湊，由<b>前端</b>算。伺服器不驗證它等於內容——
 *               驗證需要讀取整個檔案，而那正是預簽名直傳在避免的事。
 *               算錯的後果是「同一張圖存了兩份」，是浪費不是錯誤。
 *               但格式必須驗：它會變成物件鍵的一部分，而物件鍵在 URL 上
 */
public record UploadAuthorizationRequest(

        @NotBlank(message = "sha256 不可為空")
        @Pattern(regexp = "[0-9a-fA-F]{64}", message = "sha256 格式不正確")
        String sha256,

        @NotBlank(message = "contentType 不可為空")
        String contentType,

        @Positive(message = "檔案大小必須大於 0")
        long byteSize
) {
}
