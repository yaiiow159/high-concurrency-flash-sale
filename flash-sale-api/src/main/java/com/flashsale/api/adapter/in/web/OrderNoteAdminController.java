package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.application.port.in.OrderStaffNoteUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 訂單內部註記。需要 {@code seckill:admin} scope。
 *
 * <p>這支端點的內容<b>永遠不會</b>出現在買家看得到的地方——那正是它與
 * 買家備註分成兩欄的理由。
 */
@RestController
@RequestMapping("/api/v1/admin/orders/{orderNo}/staff-note")
@Tag(name = "訂單內部註記", description = "只有後台看得到")
public class OrderNoteAdminController {

    private final OrderStaffNoteUseCase staffNote;

    public OrderNoteAdminController(OrderStaffNoteUseCase staffNote) {
        this.staffNote = staffNote;
    }

    @GetMapping
    @Operation(summary = "讀取內部註記")
    public ApiResponse<NoteView> read(@PathVariable String orderNo) {
        return ApiResponse.ok(new NoteView(staffNote.read(orderNo)));
    }

    @PutMapping
    @Operation(summary = "寫入內部註記", description = "留空即清除")
    public ApiResponse<Void> write(@PathVariable String orderNo,
                                   @Valid @RequestBody NoteRequest request) {
        staffNote.write(orderNo, request.note());
        return ApiResponse.ok(null);
    }

    public record NoteView(String note) {
    }

    public record NoteRequest(
            @Size(max = 500, message = "內部註記不可超過 500 字") String note) {
    }
}
