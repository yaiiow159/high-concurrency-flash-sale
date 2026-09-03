package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.domain.aftersales.ReturnReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 退貨申請的請求體。
 *
 * <p><b>沒有金額欄位，也沒有「是否需要寄回」。</b>
 * 前者由訂單行的快照單價算出，後者由訂單狀態決定——
 * 兩個都讓呼叫端指定，等於讓買家自己決定退多少錢、要不要把貨寄回來。
 */
public record OpenReturnRequest(

        @NotEmpty(message = "至少要選一個要退的品項")
        @Size(max = 50, message = "一次最多退 50 個品項")
        @Valid
        List<Item> items,

        @NotNull(message = "請選擇退貨原因")
        ReturnReason reason,

        @Size(max = 512, message = "說明不可超過 512 字")
        String reasonDetail,

        /**
         * 冪等鍵。由前端在<b>送出前</b>產生並在重試之間保留——
         * 每次重試都換新值的話，網路逾時後再按一次就會申請兩次退貨。
         */
        @NotBlank(message = "requestId 不可為空")
        @Size(max = 64, message = "requestId 不可超過 64 字")
        String requestId
) {

    public record Item(

            @NotNull(message = "skuId 不可為空")
            Long skuId,

            @Min(value = 1, message = "退貨數量必須大於 0")
            int quantity
    ) {
    }
}
