package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;

/** 搶購入站埠：整個系統唯一的「下單」入口。 */
public interface SeckillUseCase {

    /**
     * 執行一次搶購嘗試。
     *
     * <p>成功僅代表<b>庫存預扣成功且訂單訊息已投遞</b>，不代表訂單已落庫。
     *
     * @throws com.flashsale.domain.shared.BusinessException 活動不可搶購、售罄或超過限購時
     */
    SeckillTicket attempt(SeckillCommand command);
}
