package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.security.CurrentUser;
import com.flashsale.application.port.in.ProductQuestionUseCase;
import com.flashsale.application.port.in.dto.QuestionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品問答。
 *
 * <p>讀取匿名可用——問答存在的意義就是幫「還沒買、也還沒登入」的人做決定，
 * 與評價同一個判準。
 */
@RestController
@Tag(name = "商品問答", description = "提問與查看")
public class QuestionController {

    private final ProductQuestionUseCase questions;

    public QuestionController(ProductQuestionUseCase questions) {
        this.questions = questions;
    }

    @GetMapping("/api/v1/catalog/products/{productId}/questions")
    @Operation(summary = "商品問答", description = "只列已回答並公開的")
    public ApiResponse<QuestionPage> forProduct(@PathVariable Long productId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(new QuestionPage(
                questions.publishedFor(productId, page, size),
                questions.countPublishedFor(productId)));
    }

    @PostMapping("/api/v1/catalog/products/{productId}/questions")
    @Operation(summary = "提問", description = "任何登入者都能問，不必買過")
    public ApiResponse<QuestionView> ask(@CurrentUser Long userId,
                                         @PathVariable Long productId,
                                         @Valid @RequestBody AskRequest request) {
        return ApiResponse.ok(questions.ask(userId, productId, request.content()));
    }

    @GetMapping("/api/v1/questions/mine")
    @Operation(summary = "我問過的問題", description = "含還沒公開的——問的人看得到自己的")
    public ApiResponse<List<QuestionView>> mine(@CurrentUser Long userId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(questions.myQuestions(userId, page, size));
    }

    public record AskRequest(
            @NotBlank(message = "問題不可為空")
            @Size(min = 5, max = 500, message = "問題長度需介於 5 與 500 字之間")
            String content) {
    }

    public record QuestionPage(List<QuestionView> items, long total) {
    }
}
