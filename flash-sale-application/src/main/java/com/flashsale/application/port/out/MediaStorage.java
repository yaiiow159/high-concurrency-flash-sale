package com.flashsale.application.port.out;

import java.time.Duration;
import java.util.Set;

/**
 * 物件儲存埠（出站，ADR-0027）。
 *
 * <h2>位元組不經過應用伺服器</h2>
 *
 * <p>這個埠<b>沒有 upload(bytes) 方法</b>，那是刻意的。
 * 一張 5 MB 的圖若走應用伺服器轉發，那條請求執行緒會被佔住數秒——
 * 而秒殺熱路徑要的正是那個執行緒池。營運在後台上傳商品圖，
 * 會直接吃掉當下能承接搶購的併發數。
 *
 * <p>這裡只簽 URL（純 CPU、零 I/O），位元組由瀏覽器直接送到儲存端。
 */
public interface MediaStorage {

    /**
     * 簽一個可以 PUT 的臨時 URL。
     *
     * <p>簽章綁定 content type 與大小上限——不綁的話，
     * 拿到 URL 的人可以上傳任何東西、任意大小到我們的桶裡。
     */
    String presignUpload(String objectKey, String contentType, long byteSize, Duration ttl);

    /** 物件的公開讀取網址。 */
    String publicUrl(String objectKey);

    /** 物件在不在。上傳回報之後用它確認，而不是相信前端說的。 */
    boolean exists(String objectKey);

    /**
     * 桶裡所有物件的鍵。供孤兒對帳比對。
     *
     * <p>五萬件商品的圖片量級下這是一次可接受的全掃；
     * 真的大到不能全掃時，對帳要改成分頁走訪，而那是另一個決定。
     */
    Set<String> allKeys();

    /**
     * 永久刪除一個物件。
     *
     * <p><b>對帳預設不會呼叫它</b>（ADR-0027 決策 5）——
     * 「沒有人指向這個物件」的判斷一旦有 bug，代價是永久性資料遺失，
     * 而那沒有補償路徑。這個方法存在是為了維運手動處置。
     */
    void delete(String objectKey);
}
