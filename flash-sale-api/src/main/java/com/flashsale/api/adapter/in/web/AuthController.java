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

/** 認證 API。 */
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

    /** 註冊。 */
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

    /** 續期。 */
    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "續期", description = "以 refresh token 換新令牌組；舊的 refresh token 立即失效")
    public ApiResponse<SessionTokens> refresh(@Valid @RequestBody AuthRequests.RefreshToken request) {
        return ApiResponse.ok(authenticationUseCase.refresh(request.refreshToken()));
    }

    /** 登出。 */
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
