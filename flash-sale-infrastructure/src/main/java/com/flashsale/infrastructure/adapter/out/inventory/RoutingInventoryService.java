package com.flashsale.infrastructure.adapter.out.inventory;

import com.flashsale.application.port.out.InventoryService;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.stock.StockDeductionResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 依通道把庫存操作分派給對應的機制。
 *
 * <p>這是雙模型對外的<b>唯一入口</b>。呼叫端注入 {@link InventoryService}
 * 拿到的就是這個 Bean（{@code @Primary}），不需要、也不該知道背後是 Redis 還是 MySQL。
 *
 * <p>與 {@code MultiLevelActivityRepository} 用 Decorator 藏住多級快取是同一個手法：
 * 讓「用什麼技術實現」成為可以替換的細節。ADR-0008 明確寫下了退場路徑——
 * 若秒殺不再是業務重點，刪掉 Redis 實作與下面這個 switch 的一個分支即可，
 * 所有呼叫端一行都不用改。
 *
 * <p><b>路由依據是通道，不是 SKU。</b>同一個 SKU 可能同時在秒殺與一般銷售，
 * 若依 SKU 路由就會出現「這個 SKU 該走哪邊」的模糊地帶；
 * 依通道則永遠明確。兩者不會互相干擾的保證來自劃撥（見 ADR-0008），
 * 不是來自路由規則。
 */
@Component
@Primary
public class RoutingInventoryService implements InventoryService {

    private final JdbcStandardInventory standardInventory;
    private final RedisSeckillInventory seckillInventory;

    public RoutingInventoryService(JdbcStandardInventory standardInventory,
                                   RedisSeckillInventory seckillInventory) {
        this.standardInventory = standardInventory;
        this.seckillInventory = seckillInventory;
    }

    @Override
    public StockDeductionResult deduct(DeductCommand command) {
        return route(command.channel()).deduct(command);
    }

    @Override
    public boolean restore(RestoreCommand command) {
        return route(command.channel()).restore(command);
    }

    private InventoryService route(OrderChannel channel) {
        return switch (channel) {
            case NORMAL -> standardInventory;
            case SECKILL -> seckillInventory;
        };
    }
}
