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

/** 未認證（401）的回應。 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

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
