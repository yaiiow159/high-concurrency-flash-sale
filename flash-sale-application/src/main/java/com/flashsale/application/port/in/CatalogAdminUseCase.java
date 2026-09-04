package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.ProductView;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商品上下架。
 *
 * <p>Catalog 先前<b>沒有任何寫入端點</b>，商品是遷移種進去的——
 * 沒有寫入就沒有領域事件，也就沒有東西能驅動搜尋索引（ADR-0012 決策 6）。
 *
 * <p>營運後台本來就在 P4 的清單上，只是搜尋讓它變成必須先做的那一個。
 */
public interface CatalogAdminUseCase {

    /**
     * 建立商品。
     *
     * <p><b>新建的商品一律是 {@code DRAFT}，不直接上架</b>（ADR-0015 決策 5）。
     * 上架是一個獨立的動作，因為它會觸發搜尋索引寫入——
     * 建立與曝光是兩件事，中間那段時間正是給人檢查有沒有打錯字用的。
     */
    ProductView create(CreateProductCommand command);

    /**
     * 後台的商品清單。
     *
     * <p>與 {@code CatalogQueryUseCase} 的那支不同：這裡回<b>所有狀態</b>的商品。
     * 前台只看得到上架的，而後台要看得到草稿與已下架的——
     * 看不到草稿的話，剛建好的商品就找不到入口去上架它。
     */
    List<ProductView> listAll(String status, int page, int size);

    ProductView putOnShelf(Long productId);

    /**
     * 下架。
     *
     * <p><b>下架不刪資料</b>：歷史訂單仍需要追溯「這是哪個商品」。
     * 它只會從搜尋索引移除，資料庫那筆保留。
     */
    ProductView takeOffShelf(Long productId);

    /**
     * @param skus 至少要有一個。<b>沒有 SKU 的商品不是「還沒建完」，
     *             而是一個永遠不會出現在任何地方的殘骸</b>——
     *             列表要顯示最低價，而最低價來自 SKU（ADR-0015 決策 5）
     */
    record CreateProductCommand(Long categoryId, String name, String brand,
                                String description, List<SkuSpecCommand> skus) {

        /** 一個商品的 SKU 數上限。沒有上限的話，一次請求就能寫進上萬列。 */
        public static final int MAX_SKUS = 50;

        public CreateProductCommand {
            Objects.requireNonNull(categoryId, "categoryId 不可為 null");
            if (name == null || name.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "商品名稱不可為空");
            }
            if (skus == null || skus.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "商品至少要有一個規格——沒有規格的商品無法定價，也不會出現在任何列表上");
            }
            if (skus.size() > MAX_SKUS) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "單一商品最多 %d 個規格".formatted(MAX_SKUS));
            }
            skus = List.copyOf(skus);
        }
    }

    /**
     * @param attributes 規格屬性，例如 {@code {"容量": "256G", "顏色": "黑鈦金"}}。
     *                   用 Map 而不是固定欄位：不同品類的規格維度本來就不同，
     *                   把「容量」寫成欄位的那一刻，賣衣服就得改 schema
     */
    record SkuSpecCommand(Map<String, String> attributes, BigDecimal price, String barcode) {

        public SkuSpecCommand {
            if (attributes == null || attributes.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "規格屬性不可為空");
            }
            if (price == null || price.signum() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER, "價格必須大於 0");
            }
        }
    }
}
