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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 問答後台。需要 {@code seckill:admin} scope（由 SecurityConfig 統一設定）。 */
@RestController
@RequestMapping("/api/v1/admin/questions")
@Tag(name = "問答管理", description = "回覆與下架")
public class QuestionAdminController {

    private final ProductQuestionUseCase questions;

    public QuestionAdminController(ProductQuestionUseCase questions) {
        this.questions = questions;
    }

    @GetMapping
    @Operation(summary = "待回覆清單", description = "等最久的排前面")
    public ApiResponse<QuestionController.QuestionPage> unanswered(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(new QuestionController.QuestionPage(
                questions.unanswered(page, size), questions.countUnanswered()));
    }

    @PostMapping("/{questionId}/answer")
    @Operation(summary = "回答並公開", description = "回答與公開是同一個動作")
    public ApiResponse<QuestionView> answer(@CurrentUser Long adminUserId,
                                            @PathVariable Long questionId,
                                            @Valid @RequestBody AnswerRequest request) {
        return ApiResponse.ok(questions.answer(adminUserId, questionId, request.answer()));
    }

    @PostMapping("/{questionId}/hide")
    @Operation(summary = "下架", description = "用於不當提問")
    public ApiResponse<Void> hide(@PathVariable Long questionId) {
        questions.hide(questionId);
        return ApiResponse.ok(null);
    }

    public record AnswerRequest(
            @NotBlank(message = "回答不可為空")
            @Size(max = 1000, message = "回答不可超過 1000 字")
            String answer) {
    }
}
