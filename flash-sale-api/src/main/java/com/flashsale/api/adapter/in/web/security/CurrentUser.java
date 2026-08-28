package com.flashsale.api.adapter.in.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入已認證使用者的 ID。
 *
 * <pre>
 * public ApiResponse&lt;?&gt; seckill(&#64;CurrentUser Long userId, ...)
 * </pre>
 *
 * <p><b>取代先前的 {@code @RequestHeader("X-User-Id")}。</b>
 * 差別是決定性的：標頭是呼叫端自己填的，任何人都能宣稱自己是任何人；
 * 這個註解的值來自簽章驗證過的令牌，偽造需要私鑰。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
