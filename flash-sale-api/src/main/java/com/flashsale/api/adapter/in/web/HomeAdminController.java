package com.flashsale.api.adapter.in.web;

import com.flashsale.api.adapter.in.web.dto.ApiResponse;
import com.flashsale.api.adapter.in.web.dto.CarouselSlideRequest;
import com.flashsale.api.adapter.in.web.dto.HomeSectionRequest;
import com.flashsale.application.port.in.HomeLayoutAdminUseCase;
import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 首頁版型管理。全部需要 {@code seckill:admin} scope（由 SecurityConfig 統一設定）。 */
@RestController
@RequestMapping("/api/v1/admin/home")
@Tag(name = "首頁版型管理", description = "版位與輪播圖的維護")
public class HomeAdminController {

    private final HomeLayoutAdminUseCase admin;
    private final MediaStorage mediaStorage;

    public HomeAdminController(HomeLayoutAdminUseCase admin, MediaStorage mediaStorage) {
        this.admin = admin;
        this.mediaStorage = mediaStorage;
    }

    private CarouselSlideRequest.View toView(CarouselSlide slide) {
        return CarouselSlideRequest.View.from(slide, mediaStorage.publicUrl(slide.objectKey()));
    }

    @GetMapping("/sections")
    @Operation(summary = "全部版位", description = "含停用與不在上架期間的")
    public ApiResponse<List<HomeSectionRequest.View>> sections() {
        return ApiResponse.ok(admin.allSections().stream().map(HomeSectionRequest.View::from).toList());
    }

    @PostMapping("/sections")
    @Operation(summary = "新增版位")
    public ApiResponse<HomeSectionRequest.View> createSection(
            @Valid @RequestBody HomeSectionRequest request) {
        return ApiResponse.ok(HomeSectionRequest.View.from(admin.saveSection(request.toDomain(null))));
    }

    @PutMapping("/sections/{sectionId}")
    @Operation(summary = "修改版位")
    public ApiResponse<HomeSectionRequest.View> updateSection(
            @PathVariable Long sectionId, @Valid @RequestBody HomeSectionRequest request) {
        return ApiResponse.ok(
                HomeSectionRequest.View.from(admin.saveSection(request.toDomain(sectionId))));
    }

    @DeleteMapping("/sections/{sectionId}")
    @Operation(summary = "刪除版位")
    public ApiResponse<Void> deleteSection(@PathVariable Long sectionId) {
        admin.deleteSection(sectionId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/slides")
    @Operation(summary = "全部輪播圖")
    public ApiResponse<List<CarouselSlideRequest.View>> slides() {
        return ApiResponse.ok(admin.allSlides().stream().map(this::toView).toList());
    }

    @PostMapping("/slides")
    @Operation(summary = "新增輪播圖")
    public ApiResponse<CarouselSlideRequest.View> createSlide(
            @Valid @RequestBody CarouselSlideRequest request) {
        return ApiResponse.ok(toView(admin.saveSlide(request.toDomain(null))));
    }

    @PutMapping("/slides/{slideId}")
    @Operation(summary = "修改輪播圖")
    public ApiResponse<CarouselSlideRequest.View> updateSlide(
            @PathVariable Long slideId, @Valid @RequestBody CarouselSlideRequest request) {
        return ApiResponse.ok(toView(admin.saveSlide(request.toDomain(slideId))));
    }

    @DeleteMapping("/slides/{slideId}")
    @Operation(summary = "刪除輪播圖")
    public ApiResponse<Void> deleteSlide(@PathVariable Long slideId) {
        admin.deleteSlide(slideId);
        return ApiResponse.ok(null);
    }
}
