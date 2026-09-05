package com.flashsale.application.port.in.dto;

/**
 * 上傳授權（ADR-0027 決策 2）。
 *
 * @param objectKey 內容雜湊算出來的鍵。前端上傳完要把它送回來掛載
 * @param uploadUrl 預簽名 PUT URL。<b>瀏覽器直接 PUT 到這裡</b>，
 *                  位元組不經過應用伺服器——那條執行緒是秒殺熱路徑要用的
 * @param alreadyUploaded 這個內容已經在儲存裡了（同一張圖上傳過）。
 *                        為 true 時前端<b>可以跳過上傳</b>直接掛載——
 *                        內容雜湊命名讓重複上傳變成零成本
 */
public record UploadAuthorization(String objectKey, String uploadUrl,
                                  boolean alreadyUploaded) {
}
