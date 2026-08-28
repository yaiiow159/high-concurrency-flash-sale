package com.flashsale.api.adapter.in.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.domain.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 權限不足（403）的回應。
 *
 * <p>與 401 的差別：401 是「你是誰我不知道」，403 是「我知道你是誰，但你不能做這件事」。
 * 混用會讓前端無法判斷該重新登入還是該顯示權限不足。
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public ApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        // 權限不足值得記錄：可能是設定錯誤，也可能是有人在探測管理端點。
        log.warn("拒絕存取 {} {}：權限不足", request.getMethod(), request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage()));
    }
}
