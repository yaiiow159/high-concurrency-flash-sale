package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.UserAdminUseCase;
import com.flashsale.application.port.in.dto.PageView;
import com.flashsale.application.port.in.dto.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 後台會員管理。 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "會員管理", description = "查詢、停權、恢復")
public class UserAdminController {

    private final UserAdminUseCase users;

    public UserAdminController(UserAdminUseCase users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "搜尋會員", description = "keyword 比對信箱前綴或顯示名稱")
    public ApiResponse<PageView<UserView>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(users.search(keyword, status, page, size));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "會員資料")
    public ApiResponse<UserView> find(@PathVariable Long userId) {
        return ApiResponse.ok(users.find(userId));
    }

    @PostMapping("/{userId}/suspend")
    @Operation(summary = "停權", description = "撤銷所有 refresh token；管理員與自己不可停權")
    public ApiResponse<UserView> suspend(@CurrentUser Long operatorUserId, @PathVariable Long userId) {
        return ApiResponse.ok(users.suspend(userId, operatorUserId));
    }

    @PostMapping("/{userId}/reactivate")
    @Operation(summary = "恢復")
    public ApiResponse<UserView> reactivate(@PathVariable Long userId) {
        return ApiResponse.ok(users.reactivate(userId));
    }
}
