package com.flashsale.domain.shared;

/** 分頁請求。 */
public record Page(int number, int size) {

    /** 預設頁大小。呼叫端沒有指定時用它。 */
    public static final int DEFAULT_SIZE = 20;

    /** 建立一個分頁請求，把不合法的輸入夾成合法的。 */
    public static Page of(int number, int size, int maxSize) {
        return new Page(
                Math.max(number, 0),
                Math.clamp(size <= 0 ? DEFAULT_SIZE : size, 1, Math.max(maxSize, 1)));
    }

    /** 從 {@code (limit, offset)} 還原。 */
    public static Page fromOffset(int limit, int offset) {
        int safeLimit = Math.max(limit, 1);
        return new Page(Math.max(offset, 0) / safeLimit, safeLimit);
    }

    /** 這一頁的起始位移。 */
    public int offset() {
        return number * size;
    }

    /** 多取一筆，用來判斷還有沒有下一頁。 */
    public int sizePlusOne() {
        return size + 1;
    }
}
