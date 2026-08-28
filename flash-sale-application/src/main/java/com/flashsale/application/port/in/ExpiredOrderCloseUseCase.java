package com.flashsale.application.port.in;

/**
 * 逾期訂單關單入站埠，由排程驅動。
 *
 * <p>秒殺的預扣庫存不能無限期佔著——搶到不付款的使用者若不關單退庫，
 * 商品就會「賣不掉又下不了架」。
 */
public interface ExpiredOrderCloseUseCase {

    /**
     * 關閉一批逾期未付款的訂單並登記退庫事件。
     *
     * @return 本次關閉的訂單數；回傳值等於批次上限時代表可能還有殘留，排程應立刻再跑一輪
     */
    int closeExpiredOrders();
}
