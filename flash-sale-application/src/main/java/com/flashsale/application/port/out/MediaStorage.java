package com.flashsale.application.port.out;

import java.time.Duration;
import java.util.Set;

/** 物件儲存埠（出站，ADR-0027）。 */
public interface MediaStorage {

    /** 簽一個可以 PUT 的臨時 URL。 */
    String presignUpload(String objectKey, String contentType, long byteSize, Duration ttl);

    /** 讀回一個物件的位元組。 */
    byte[] download(String objectKey);

    /**
     * 直接寫入一個物件。同樣<b>只給慢車道用</b>。
     */
    void put(String objectKey, byte[] content, String contentType);

    /** 物件的公開讀取網址。 */
    String publicUrl(String objectKey);

    /** 物件在不在。上傳回報之後用它確認，而不是相信前端說的。 */
    boolean exists(String objectKey);

    /** 桶裡所有物件的鍵。供孤兒對帳比對。 */
    Set<String> allKeys();

    /** 永久刪除一個物件。 */
    void delete(String objectKey);
}
