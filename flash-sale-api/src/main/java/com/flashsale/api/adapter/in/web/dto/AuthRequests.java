package com.flashsale.api.adapter.in.web.dto;

import com.flashsale.application.port.in.command.LoginCommand;
import com.flashsale.application.port.in.command.RegisterUserCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 認證相關的請求體。
 *
 * <p>集中在一個檔案裡，因為它們是同一組緊密相關的小契約，
 * 拆成三個檔案只會增加瀏覽成本而不增加清晰度。
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** 註冊。 */
    public record Register(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = "密碼長度需介於 8 至 72 個字元") String password,
            @NotBlank @Size(max = 50) String displayName
    ) {
        public RegisterUserCommand toCommand() {
            return new RegisterUserCommand(email, password, displayName);
        }
    }

    /**
     * 登入。
     *
     * <p>刻意<b>不</b>對密碼加 {@code @Size} 之類的格式驗證——
     * 那會讓「密碼長度不足」與「帳號密碼錯誤」回不同的錯誤，
     * 攻擊者可據此推斷密碼規則。登入只該有一種失敗結果。
     */
    public record Login(
            @NotBlank String email,
            @NotBlank String password
    ) {
        public LoginCommand toCommand() {
            return new LoginCommand(email, password);
        }
    }

    /** 續期與登出共用；refresh token 由用戶端保管並回傳。 */
    public record RefreshToken(@NotBlank String refreshToken) {
    }
}
