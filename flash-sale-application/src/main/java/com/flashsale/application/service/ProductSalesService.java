package com.flashsale.application.service;

import com.flashsale.application.port.in.ProductSalesUseCase;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ProductRepository;
import com.flashsale.application.port.out.ProductSalesRepository;
import com.flashsale.domain.catalog.Product;
import com.flashsale.domain.catalog.Sku;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.order.OrderNo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 銷量的計入與扣回。
 *
 * <p>訂單行記的是 SKU，而銷量聚合是<b>以商品為單位</b>——
 * 同一件商品的不同規格要合併計算，否則「暢銷商品」會被規格拆散。
 */
@Service
public class ProductSalesService implements ProductSalesUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductSalesRepository salesRepository;

    public ProductSalesService(OrderRepository orderRepository,
                               ProductRepository productRepository,
                               ProductSalesRepository salesRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.salesRepository = salesRepository;
    }

    @Override
    @Transactional
    public boolean recordSale(String orderNo, Long userId) {
        return resolveQuantities(orderNo)
                .map(quantities -> salesRepository.applySale(orderNo, quantities))
                .orElse(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordReturn(String returnNo, Map<Long, Integer> quantityBySku) {
        // MANDATORY：這一步要與退款在**同一個交易**裡。
        // 走事件的話會開一個窗口——錢已經退了、銷量還沒扣，
        // 與積分扣回是同一個判斷（兩邊都是這個資料庫，不跨資源）
        Map<Long, Integer> byProduct = toProductQuantities(quantityBySku);
        return !byProduct.isEmpty() && salesRepository.applyReturn(returnNo, byProduct);
    }

    /**
     * 把訂單行的 SKU 換算成「商品 → 件數」。
     *
     * <p>訂單查不到時回 empty 而不是拋錯：往外丟會讓事件一直重試，
     * 而重試永遠不會成功。銷量是顯示用的衍生資料，
     * 漏記一筆的代價遠低於卡住整個分區——這是 fail-open。
     */
    /** SKU → 件數，換算成商品 → 件數。同一件商品的不同規格要合併。 */
    private Map<Long, Integer> toProductQuantities(Map<Long, Integer> quantityBySku) {
        Map<Long, Long> productBySku = productRepository
                .findBySkuIds(List.copyOf(quantityBySku.keySet())).stream()
                .flatMap(product -> product.skus().stream()
                        .map(sku -> Map.entry(sku.id(), product.id())))
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

        Map<Long, Integer> byProduct = new HashMap<>();
        quantityBySku.forEach((skuId, quantity) -> {
            Long productId = productBySku.get(skuId);
            if (productId != null) {
                byProduct.merge(productId, quantity, Integer::sum);
            }
        });
        return byProduct;
    }

    private Optional<Map<Long, Integer>> resolveQuantities(String orderNo) {
        Optional<Order> found = orderRepository.findByOrderNo(OrderNo.of(orderNo));
        if (found.isEmpty()) {
            log.warn("訂單 {} 查不到，銷量未計入", orderNo);
            return Optional.empty();
        }

        List<OrderLine> lines = found.get().lines();
        Map<Long, Long> productBySku = productRepository
                .findBySkuIds(lines.stream().map(OrderLine::skuId).toList()).stream()
                .flatMap(product -> product.skus().stream()
                        .map(sku -> Map.entry(sku.id(), product.id())))
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

        Map<Long, Integer> quantities = new HashMap<>();
        for (OrderLine line : lines) {
            Long productId = productBySku.get(line.skuId());
            if (productId == null) {
                // 商品被刪除。略過這一行而不是整筆放棄——
                // 其他行的銷量仍然是真實發生過的事
                continue;
            }
            quantities.merge(productId, line.quantity(), Integer::sum);
        }
        return quantities.isEmpty() ? Optional.empty() : Optional.of(quantities);
    }
}
