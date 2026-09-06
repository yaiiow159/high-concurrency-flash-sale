package com.flashsale.application.port.in.command;

import com.flashsale.domain.aftersales.ReturnReason;

import java.util.List;

/** 開立退貨申請。 */
public record OpenReturnCommand(
        String orderNo,
        Long userId,
        String requestId,
        List<Item> items,
        ReturnReason reason,
        String reasonDetail
) {

    public record Item(Long skuId, int quantity) {
    }
}
