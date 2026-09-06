package com.flashsale.application.port.in;

import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;

/** 搶購入站埠：整個系統唯一的「下單」入口。 */
public interface SeckillUseCase {

    /** 執行一次搶購嘗試。 */
    SeckillTicket attempt(SeckillCommand command);
}
