package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.AddressRequest;
import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.AddressUseCase;
import com.flashsale.application.port.in.dto.AddressView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收貨地址簿 API。
 *
 * <p>全部需要登入，且<b>每個操作都以令牌的 userId 為界</b>——
 * 路徑上的 addressId 只是「哪一筆」，不是「誰的」。
 * 擁有者檢查在聚合根裡，不在這裡：Controller 只負責把身分傳下去。
 *
 * <p>查不到與無權限都回 404。回 403 等於告訴攻擊者這個 ID 是有效的，
 * 讓他能靠窮舉列舉出系統裡有多少地址。
 */
@RestController
@RequestMapping("/api/v1/addresses")
@Tag(name = "收貨地址", description = "地址簿管理")
public class AddressController {

    private final AddressUseCase addressUseCase;

    public AddressController(AddressUseCase addressUseCase) {
        this.addressUseCase = addressUseCase;
    }

    @GetMapping
    @Operation(summary = "地址列表", description = "預設地址排最前")
    public ApiResponse<List<AddressView>> list(@CurrentUser Long userId) {
        return ApiResponse.ok(addressUseCase.list(userId));
    }

    @PostMapping
    @Operation(summary = "新增地址", description = "第一筆會自動成為預設")
    public ResponseEntity<ApiResponse<AddressView>> add(
            @Valid @RequestBody AddressRequest request,
            @CurrentUser Long userId) {

        AddressView created = addressUseCase.add(userId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * 修改地址。
     *
     * <p>已成立的訂單完全不受影響——它們存的是快照而非引用。
     * 這正是快照設計換來的自由。
     */
    @PutMapping("/{addressId}")
    @Operation(summary = "修改地址", description = "不影響已成立的訂單")
    public ApiResponse<AddressView> update(@PathVariable Long addressId,
                                           @Valid @RequestBody AddressRequest request,
                                           @CurrentUser Long userId) {
        return ApiResponse.ok(addressUseCase.update(userId, addressId, request.toCommand()));
    }

    @DeleteMapping("/{addressId}")
    @Operation(summary = "刪除地址", description = "刪掉預設地址時會自動補上另一筆")
    public ResponseEntity<Void> delete(@PathVariable Long addressId, @CurrentUser Long userId) {
        addressUseCase.delete(userId, addressId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{addressId}/default")
    @Operation(summary = "設為預設地址")
    public ApiResponse<AddressView> setDefault(@PathVariable Long addressId,
                                               @CurrentUser Long userId) {
        return ApiResponse.ok(addressUseCase.setDefault(userId, addressId));
    }
}
