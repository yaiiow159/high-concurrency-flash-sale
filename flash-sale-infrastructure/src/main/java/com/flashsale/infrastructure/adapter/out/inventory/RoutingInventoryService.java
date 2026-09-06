package com.flashsale.infrastructure.adapter.out.inventory;

import com.flashsale.application.port.out.InventoryService;
import com.flashsale.domain.order.OrderChannel;
import com.flashsale.domain.stock.StockDeductionResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 依通道把庫存操作分派給對應的機制。 */
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
