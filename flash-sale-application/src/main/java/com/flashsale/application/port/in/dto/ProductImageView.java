package com.flashsale.application.port.in.dto;

/**
 * 商品圖片。
 *
 * <p><b>後端決定每個用途該用哪個尺寸，前端不猜。</b>
 * 讓前端自己拼變體網址的話，它得知道命名規則——而那是後端的
 * 實作細節，改了就會全站破圖。變體還沒產生（或永遠不會，例如 WebP）時
 * 這些欄位會是原圖的網址。
 *
 * @param url      商品頁主視覺用
 * @param listUrl  列表卡片用
 * @param thumbUrl 縮圖列與後台用
 */
public record ProductImageView(Long imageId, String url, String listUrl,
                               String thumbUrl, int sortOrder) {
}
