package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductView;

import java.util.List;

/** 前台排行榜。 */
public interface ProductRankingUseCase {

    /**
     * 最近 {@code days} 天的熱銷商品，依已付款訂單的銷量排序。
     *
     * <p>與後台銷售報表用同一條查詢：只算已付款的單，待付款的會在逾時關單後消失，
     * 那種名次沒有人敢信。已下架的商品不列——排行榜是入口，點進去買不到就是壞掉的入口。
     */
    List<RankedProduct> bestSellers(int days, int limit);

    record RankedProduct(int rank, ProductView product, long soldQuantity) {
    }
}
