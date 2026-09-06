package com.flashsale.infrastructure.adapter.out.inventory;

import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.stock.StockDeductionResult;
import org.springframework.stereotype.Component;

/** 秒殺商品的庫存機制：Redis + Lua。 */
@Component
public class RedisSeckillInventory implements InventoryService {

    private final StockRepository stockRepository;

    public RedisSeckillInventory(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public StockDeductionResult deduct(DeductCommand command) {
        return stockRepository.deduct(
                command.activityId(), command.userId(), command.quantity(),
                command.perUserLimit(), command.requestId(), command.orderNo());
    }

    @Override
    public boolean restore(RestoreCommand command) {
        return stockRepository.restore(command.activityId(), command.userId(),
                command.quantity(), command.requestId());
    }
}
