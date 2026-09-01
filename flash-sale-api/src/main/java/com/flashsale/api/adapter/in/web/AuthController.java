package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.AuthRequests;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.AuthenticationUseCase;
import com.flashsale.application.port.in.UserQueryUseCase;
import com.flashsale.application.port.in.UserRegistrationUseCase;
import com.flashsale.application.port.in.dto.SessionTokens;
import com.flashsale.application.port.in.dto.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 認證 API。
 *
 * <p><b>取代了先前的開發用發證端點</b>。那個端點不做任何身分驗證，
 * 存在的唯一理由是「沒有別的方式能拿到令牌」——現在有了真正的註冊登入，
 * 那個理由不再成立，後門就該關掉。
 *
 * <p>令牌設計見 {@code RefreshToken} 聚合根：
 * access token 是短命的 JWT（無狀態、驗證零成本），
 * refresh token 是可撤銷的不透明字串（每次續期輪替，並偵測重用）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "認證", description = "註冊、登入、續期、登出")
public class AuthController {

    private final UserRegistrationUseCase registrationUseCase;
    private final AuthenticationUseCase authenticationUseCase;
    private final UserQueryUseCase userQueryUseCase;

    public AuthController(UserRegistrationUseCase registrationUseCase,
                          AuthenticationUseCase authenticationUseCase,
                          UserQueryUseCase userQueryUseCase) {
        this.registrationUseCase = registrationUseCase;
        this.authenticationUseCase = authenticationUseCase;
        this.userQueryUseCase = userQueryUseCase;
    }

    /**
     * 註冊。
     *
     * <p>回 201 而非 200：這裡確實建立了一個新資源。
     * 與搶購的 202 形成對照——狀態碼要誠實反映系統做了什麼。
     */
    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "註冊帳號")
    public ResponseEntity<ApiResponse<UserView>> register(@Valid @RequestBody AuthRequests.Register request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(registrationUseCase.register(request.toCommand())));
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "登入", description = "回傳 access token 與 refresh token")
    public ApiResponse<SessionTokens> login(@Valid @RequestBody AuthRequests.Login request) {
        return ApiResponse.ok(authenticationUseCase.login(request.toCommand()));
    }

    /**
     * 續期。
     *
     * <p>每次呼叫都會<b>輪替</b>——舊的 refresh token 立刻失效，用戶端必須改用新的。
     * 若拿已輪替過的 token 來換，代表該憑證曾外洩，整條輪替鏈會被撤銷。
     */
    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "續期", description = "以 refresh token 換新令牌組；舊的 refresh token 立即失效")
    public ApiResponse<SessionTokens> refresh(@Valid @RequestBody AuthRequests.RefreshToken request) {
        return ApiResponse.ok(authenticationUseCase.refresh(request.refreshToken()));
    }

    /**
     * 登出。
     *
     * <p>回 204 且<b>一律成功</b>——即使 token 無效也不回報錯誤。
     * 回報「這個 token 不存在」等於提供一支驗證 token 是否有效的 API。
     */
    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(summary = "登出", description = "撤銷 refresh token；無效的 token 靜默忽略")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthRequests.RefreshToken request) {
        authenticationUseCase.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "目前登入的使用者")
    public ApiResponse<UserView> me(@CurrentUser Long userId) {
        return ApiResponse.ok(userQueryUseCase.findById(userId));
    }
}
