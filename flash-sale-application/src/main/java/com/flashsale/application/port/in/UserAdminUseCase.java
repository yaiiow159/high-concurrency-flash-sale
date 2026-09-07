package com.flashsale.application.port.in;

import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.UserView;

/** 後台的會員查詢與停權。 */
public interface UserAdminUseCase {

    /** keyword 比對信箱前綴或顯示名稱；status 可為 null。 */
    PageView<UserView> search(String keyword, String status, int page, int size);

    UserView find(Long userId);

    /**
     * 停權。成功代表：狀態已改、該使用者所有 refresh token 已撤銷、一般結帳會被拒絕。
     * 已簽發的 access token 在到期前（最長 15 分鐘）仍可讀取資料——那是 JWT 的取捨，
     * 秒殺熱路徑不會為了它查資料庫。管理員不可被停權，自己也不可停權自己。
     */
    UserView suspend(Long targetUserId, Long operatorUserId);

    UserView reactivate(Long targetUserId);
}
