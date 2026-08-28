package com.flashsale.api.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.domain.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未認證（401）的回應。
 *
 * <p><b>為什麼需要自訂？</b> Security 過濾鏈在 DispatcherServlet <b>之前</b>執行，
 * 因此 {@code @RestControllerAdvice} 完全攔不到認證失敗——
 * 不處理的話，401 會回 Spring 的預設格式，與其他端點的 {@code ApiResponse} 結構不一致，
 * 前端就得為認證錯誤寫一套特殊解析。
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        // 依 RFC 6750 回 WWW-Authenticate，讓標準用戶端知道該用哪種憑證重試。
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // 訊息刻意保持籠統：不透露是「令牌過期」「簽章錯誤」還是「受眾不符」——
        // 這些細節能幫助攻擊者逐步試探出有效的令牌結構。詳情記在伺服器日誌。
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage()));
    }
}
