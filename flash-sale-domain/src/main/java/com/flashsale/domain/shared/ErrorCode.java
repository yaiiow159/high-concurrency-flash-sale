package com.flashsale.domain.shared;

/**
 * 全站錯誤碼。
 *
 * <p>編碼規則：{@code A}=呼叫端錯誤、{@code B}=業務規則拒絕、{@code C}=系統/依賴故障。
 * 前端可依前綴決定是否重試：{@code C} 可重試，{@code A}/{@code B} 不可。
 */
public enum ErrorCode {

    // ---- A：呼叫端錯誤 ----
    INVALID_PARAMETER("A0001", "請求參數不合法"),
    RATE_LIMITED("A0002", "請求過於頻繁，請稍後再試"),
    DUPLICATE_REQUEST("A0003", "重複的請求"),
    UNAUTHENTICATED("A0004", "尚未登入或憑證已失效"),
    FORBIDDEN("A0005", "沒有執行此操作的權限"),
    // 登入失敗一律用同一個錯誤碼與訊息，不區分「信箱不存在」與「密碼錯誤」——
    // 區分開來等於提供一支帳號枚舉的 API。
    INVALID_CREDENTIALS("A0006", "帳號或密碼錯誤"),
    INVALID_REFRESH_TOKEN("A0007", "登入憑證已失效，請重新登入"),
    INVALID_CALLBACK_SIGNATURE("A0008", "回調簽章驗證失敗"),

    // ---- B：業務規則拒絕 ----
    ACTIVITY_NOT_FOUND("B0001", "活動不存在"),
    ACTIVITY_NOT_STARTED("B0002", "活動尚未開始"),
    ACTIVITY_ENDED("B0003", "活動已結束"),
    ACTIVITY_OFFLINE("B0004", "活動未上架"),
    SOLD_OUT("B0005", "商品已售罄"),
    USER_PURCHASE_LIMIT_EXCEEDED("B0006", "已達個人限購數量"),
    ORDER_NOT_FOUND("B0007", "訂單不存在"),
    ILLEGAL_ORDER_STATE_TRANSITION("B0008", "訂單狀態不允許此操作"),
    ILLEGAL_PAYMENT_STATE_TRANSITION("B0012", "付款狀態不允許此操作"),
    PAYMENT_NOT_FOUND("B0013", "付款單不存在"),
    ORDER_NOT_PAYABLE("B0014", "此訂單目前無法付款"),
    EMAIL_ALREADY_REGISTERED("B0009", "此電子郵件已被註冊"),
    ACCOUNT_SUSPENDED("B0010", "帳號已停權"),
    USER_NOT_FOUND("B0011", "使用者不存在"),

    // ---- C：系統/依賴故障 ----
    STOCK_SERVICE_UNAVAILABLE("C0001", "庫存服務暫時不可用"),
    LOCK_ACQUIRE_FAILED("C0002", "取得分散式鎖失敗"),
    MESSAGE_PUBLISH_FAILED("C0003", "訊息投遞失敗"),
    SYSTEM_BUSY("C0004", "系統繁忙，請稍後再試");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** 是否為呼叫端可安全重試的錯誤。 */
    public boolean retryable() {
        return code.startsWith("C");
    }
}
