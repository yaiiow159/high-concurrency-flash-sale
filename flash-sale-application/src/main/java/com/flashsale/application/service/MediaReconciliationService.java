package com.flashsale.application.service;

import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.application.port.out.ProductImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 圖片對帳（ADR-0027 決策 5）。
 *
 * <h2>系統的第六條對帳，也是第一條與外部儲存比對的</h2>
 *
 * <p>物件儲存<b>不能參與資料庫的交易</b>，所以兩種失敗必有其一：
 * 先刪物件會破圖，先刪資料庫列會留孤兒。這個系統選了孤兒——
 * 孤兒只花錢，破圖直接砸在客人臉上。
 *
 * <p>因此孤兒是<b>預期會累積的</b>，需要有人定期看。
 *
 * <h2>只報告，不刪除</h2>
 *
 * <p>與庫存對帳同一個立場（CLAUDE.md 規則 8）：
 * 「沒有人指向這個物件」這個判斷一旦有 bug，代價是<b>永久性的資料遺失</b>，
 * 而那沒有補償路徑。庫存算錯還能退回來，圖片刪掉就沒了。
 *
 * <p>因此這裡<b>沒有自動修復的開關</b>——不是預設關閉，是根本不提供。
 * 要刪的話由維運看過報告之後手動處置。
 */
@Service
public class MediaReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(MediaReconciliationService.class);

    /** 報告裡最多列幾個鍵。全部列出來會讓一次事故的日誌塞滿幾萬行。 */
    private static final int MAX_SAMPLE = 50;

    private final ProductImageRepository imageRepository;
    private final MediaStorage storage;
    private final Clock clock;
    private final Duration grace;

    public MediaReconciliationService(ProductImageRepository imageRepository,
                                      MediaStorage storage, Clock clock,
                                      OrphanGrace grace) {
        this.imageRepository = imageRepository;
        this.storage = storage;
        this.clock = clock;
        this.grace = grace.value();
    }

    /** 孤兒的寬限期。必須明顯長於任何進行中的上傳流程。 */
    public record OrphanGrace(Duration value) {
    }

    /**
     * @param orphanKeys   桶裡有、但沒有人指向、且已過寬限期的物件
     * @param missingKeys  資料庫指向、但桶裡找不到的物件。<b>這一種比孤兒嚴重</b>——
     *                     它就是破圖，而且已經發生在使用者眼前
     * @param inFlight     還在寬限期內的物件。它們看起來像孤兒但不是，
     *                     列出來是為了讓報告的數字對得起來
     */
    public record MediaReconciliation(int orphanCount, int missingCount, int inFlightCount,
                                      List<String> orphanKeys, List<String> missingKeys,
                                      String verdict) {
    }

    @Transactional(readOnly = true)
    public MediaReconciliation reconcile() {
        Set<String> stored = storage.allKeys();
        Set<String> referenced = imageRepository.allReferencedKeys();
        // 剛簽發授權、還沒掛上的物件看起來就是孤兒。
        // 少了這一步，一個正在上傳中的檔案會被報成待刪除
        Set<String> recent = imageRepository.keysAuthorizedAfter(
                clock.instant().minus(grace));

        Set<String> orphans = new TreeSet<>(stored);
        orphans.removeAll(referenced);
        int inFlight = (int) orphans.stream().filter(recent::contains).count();
        orphans.removeAll(recent);

        Set<String> missing = new TreeSet<>(referenced);
        missing.removeAll(stored);

        String verdict = missing.isEmpty()
                ? (orphans.isEmpty() ? "CLEAN" : "ORPHANS")
                // 破圖已經發生在使用者眼前，比孤兒嚴重得多，
                // 因此只要有 missing 就用它當結論
                : "MISSING_OBJECTS";

        if (!missing.isEmpty()) {
            log.error("圖片對帳：{} 個物件被資料庫指向但儲存裡找不到（破圖）", missing.size());
        } else if (!orphans.isEmpty()) {
            log.warn("圖片對帳：{} 個孤兒物件（不會自動刪除）", orphans.size());
        }

        return new MediaReconciliation(orphans.size(), missing.size(), inFlight,
                orphans.stream().limit(MAX_SAMPLE).toList(),
                missing.stream().limit(MAX_SAMPLE).toList(),
                verdict);
    }
}
