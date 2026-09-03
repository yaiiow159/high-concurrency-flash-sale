package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.NotificationUseCase;
import com.flashsale.application.port.in.dto.NotificationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 站內信 API。
 *
 * <p><b>沒有管理端點。</b>通知一律由領域事件產生，不開「手動發送」的介面——
 * 那條路徑會繞過冪等鍵（{@code sourceEventId} 沒有對應的事件可用），
 * 而一個能繞過冪等的入口遲早會被用來重複發送。
 * 需要補發時應該重投事件，而不是另開一條路。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "通知", description = "站內信查詢與已讀標記")
public class NotificationController {

    private final NotificationUseCase notificationUseCase;

    public NotificationController(NotificationUseCase notificationUseCase) {
        this.notificationUseCase = notificationUseCase;
    }

    @GetMapping
    @Operation(summary = "我的站內信", description = "新到舊")
    public ApiResponse<List<NotificationView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser Long userId) {
        return ApiResponse.ok(notificationUseCase.listForUser(userId, page, size));
    }

    /**
     * 未讀數。
     *
     * <p>與列表分開的端點：導覽列的紅點每頁都需要它，
     * 而那些頁面不會順便載入整份通知列表。
     */
    @GetMapping("/unread-count")
    @Operation(summary = "未讀數量", description = "供導覽列的紅點使用")
    public ApiResponse<Map<String, Long>> unreadCount(@CurrentUser Long userId) {
        return ApiResponse.ok(Map.of("count", notificationUseCase.unreadCount(userId)));
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "標記已讀", description = "只能標記自己的；重複標記不視為錯誤")
    public ApiResponse<NotificationView> markRead(@PathVariable Long notificationId,
                                                  @CurrentUser Long userId) {
        return ApiResponse.ok(notificationUseCase.markRead(notificationId, userId));
    }

    @PostMapping("/read-all")
    @Operation(summary = "全部標記已讀")
    public ApiResponse<Map<String, Integer>> markAllRead(@CurrentUser Long userId) {
        return ApiResponse.ok(Map.of("marked", notificationUseCase.markAllRead(userId)));
    }
}
