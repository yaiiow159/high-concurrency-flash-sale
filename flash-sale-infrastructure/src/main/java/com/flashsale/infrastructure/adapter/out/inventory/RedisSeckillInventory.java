package com.flashsale.infrastructure.adapter.out.inventory;

import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.stock.StockDeductionResult;
import org.springframework.stereotype.Component;

/**
 * 秒殺商品的庫存機制：Redis + Lua。
 *
 * <p>只是把統一介面轉接到既有的 {@link StockRepository}——
 * 防超賣的實質保證仍在 {@code seckill_deduct.lua} 裡，這一層不增加任何邏輯。
 *
 * <p><b>刻意做成薄轉接而非把 Lua 邏輯搬進來</b>：秒殺扣減是全系統唯一
 * 被 1000 執行緒併發測試驗證過的路徑（{@code RedisStockRepositoryTest}），
 * 為了統一介面而重寫它，等於把已經證明正確的東西重新推上賭桌。
 */
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
