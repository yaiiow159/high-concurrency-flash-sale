package com.flashsale.domain.shared;

/**
 * 分頁請求。
 *
 * <h2>為什麼分頁要有型別</h2>
 *
 * <p>先前 {@code (int page, int size)} 兩個裸 int 一路從 Controller 傳到倉庫，
 * 而<b>每一層各自決定要不要防禦</b>。結果是同一件事有三種寫法：
 *
 * <ul>
 *   <li>12 處 {@code Math.clamp(size, 1, MAX)}，散在 Controller 與應用服務</li>
 *   <li>11 處 {@code PageRequest.of(offset / Math.max(limit, 1), limit)}，
 *       其中 2 處漏了 {@code Math.max}——{@code limit=0} 會除以零</li>
 *   <li>評價那條路徑<b>只有 Controller 夾</b>，服務層直接把 size 傳下去，
 *       於是任何非 Controller 的呼叫端都會把未夾的值送進倉庫</li>
 * </ul>
 *
 * <p>把它變成一個值物件之後，「合法的分頁」在<b>建構當下</b>就成立，
 * 下游不必再各自防禦——與專案既有的
 * 「值物件優先於裸 String／Long」（見 {@code OrderNo}）同一個理由。
 *
 * @param number 第幾頁，從 0 起算
 * @param size   每頁筆數，已夾在 {@code [1, maxSize]}
 */
public record Page(int number, int size) {

    /** 預設頁大小。呼叫端沒有指定時用它。 */
    public static final int DEFAULT_SIZE = 20;

    /**
     * 建立一個分頁請求，把不合法的輸入夾成合法的。
     *
     * <p><b>夾取而不是報錯</b>：`size=0` 或負數幾乎都是呼叫端漏帶參數，
     * 而為此回一個 400 只會讓「列表打不開」變成一個要查的問題。
     * 上限則必須存在——列表是對外開放的端點，
     * 沒有上限的話任何人都能用 {@code size=1000000} 讓資料庫掃全表。
     */
    public static Page of(int number, int size, int maxSize) {
        return new Page(
                Math.max(number, 0),
                Math.clamp(size <= 0 ? DEFAULT_SIZE : size, 1, Math.max(maxSize, 1)));
    }

    /**
     * 從 {@code (limit, offset)} 還原。
     *
     * <p>倉庫的埠用的是 limit/offset，而 Spring Data 要的是頁碼——
     * 這個換算先前在 11 個倉庫各寫一次，其中兩處漏了除零守衛。
     */
    public static Page fromOffset(int limit, int offset) {
        int safeLimit = Math.max(limit, 1);
        return new Page(Math.max(offset, 0) / safeLimit, safeLimit);
    }

    /** 這一頁的起始位移。 */
    public int offset() {
        return number * size;
    }

    /**
     * 多取一筆，用來判斷還有沒有下一頁。
     *
     * <p>比再打一次 {@code COUNT(*)} 便宜得多——在 5 萬列上那個 count
     * 比查詢本身還貴，而它只是為了決定一個布林值。
     */
    public int sizePlusOne() {
        return size + 1;
    }
}
