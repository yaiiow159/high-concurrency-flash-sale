package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 例外對應到 HTTP 語意。
 *
 * <p>這裡守的是<b>呼叫端能不能做出正確的決定</b>：
 * 該重試的要看得出可以重試，不該重試的不要讓它一直打。
 */
@DisplayName("全域例外處理")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("資料庫容量問題")
    class DatabaseCapacity {

        @Test
        @DisplayName("拿不到連線回 503 且標記可重試，不是 500")
        void connectionExhaustionIsRetryable() {
            // 實測成因：所有請求搶同一個 SKU 的庫存列，等在行鎖上的請求
            // 一直握著連線，連線池被佔滿，後面的請求連交易都開不起來。
            //
            // 這是容量問題不是程式錯誤。回 500「系統異常」的話，
            // 客戶端不會重試——而這恰恰是重試就會好的那一種
            ResponseEntity<ApiResponse<Void>> response = handler.handleDatabaseCapacity(
                    new CannotCreateTransactionException("Could not open JPA EntityManager"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(ErrorCode.SYSTEM_BUSY.code());
            assertThat(response.getBody().retryable()).isTrue();
        }

        @Test
        @DisplayName("鎖等待逾時同樣視為容量問題")
        void lockAcquisitionFailureIsRetryable() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleDatabaseCapacity(
                    new CannotAcquireLockException("Lock wait timeout exceeded"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("查詢逾時同樣視為容量問題")
        void queryTimeoutIsRetryable() {
            ResponseEntity<ApiResponse<Void>> response = handler.handleDatabaseCapacity(
                    new QueryTimeoutException("Statement timed out"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("對外訊息不含例外細節")
        void doesNotLeakInternals() {
            // 例外訊息裡有連線池名稱、SQL 片段，有時還有連線字串。
            // 那些屬於伺服器日誌，不屬於回應
            ResponseEntity<ApiResponse<Void>> response = handler.handleDatabaseCapacity(
                    new CannotCreateTransactionException(
                            "HikariPool-1 - Connection is not available, jdbc:mysql://db:3306"));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .doesNotContain("Hikari")
                    .doesNotContain("jdbc:");
        }
    }

    @Nested
    @DisplayName("兜底")
    class Fallback {

        @Test
        @DisplayName("真的未預期的例外仍然回 500")
        void unexpectedStaysFiveHundred() {
            // 把容量問題拉出來之後，兜底才回得到它原本的用途：
            // 「這裡有一個沒有人想過的錯誤」。全部混在一起的話，
            // 真正的程式錯誤會被尖峰噪音淹沒
            ResponseEntity<ApiResponse<Void>> response =
                    handler.handleUnexpected(new NullPointerException("boom"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
