package com.flashsale.application.port.out;

import com.flashsale.domain.shipping.ShippingRate;

import java.util.List;

/**
 * 運費費率的持久化埠（出站）。
 *
 * <p>費率放資料庫而不是程式碼（ADR-0019 決策 4）：運費是營運會調的東西
 * （換物流商、油價、促銷檔期），而每次調整都要改程式碼並重新部署是不合理的。
 */
public interface ShippingRateRepository {

    /**
     * 全部費率。
     *
     * <p><b>一次全取而不是依條件查。</b> 費率表只有個位數到十幾筆，
     * 而下單路徑上每多一次帶條件的查詢就多一次索引選擇的風險。
     * 全取之後由 {@code ShippingRate.select} 這個純函式挑——
     * 那也讓「5 公斤的離島訂單運費是多少」變成測得到的問題。
     */
    List<ShippingRate> findAll();
}
