package com.flashsale.application.service;

import com.flashsale.application.port.in.HomeLayoutAdminUseCase;
import com.flashsale.application.port.out.HomeLayoutRepository;
import com.flashsale.application.port.out.MediaStorage;
import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 首頁版型的後台維護。 */
@Service
public class HomeLayoutAdminService implements HomeLayoutAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(HomeLayoutAdminService.class);

    private final HomeLayoutRepository layoutRepository;
    private final MediaStorage mediaStorage;

    public HomeLayoutAdminService(HomeLayoutRepository layoutRepository,
                                  MediaStorage mediaStorage) {
        this.layoutRepository = layoutRepository;
        this.mediaStorage = mediaStorage;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomeSection> allSections() {
        return layoutRepository.findAllSections();
    }

    @Override
    @Transactional
    public HomeSection saveSection(HomeSection section) {
        HomeSection saved = layoutRepository.saveSection(section);
        log.info("首頁版位已儲存 id={}, type={}, title={}",
                saved.id(), saved.type(), saved.title());
        return saved;
    }

    @Override
    @Transactional
    public void deleteSection(Long sectionId) {
        layoutRepository.deleteSection(sectionId);
        log.info("首頁版位已刪除 id={}", sectionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CarouselSlide> allSlides() {
        return layoutRepository.findAllSlides();
    }

    /**
     * 存輪播圖。
     *
     * <p><b>先確認物件真的在</b>，而不是相信前端說的——掛上一個不存在的物件
     * 就是首頁最上方一張破圖，而那是全站最顯眼的位置。
     */
    @Override
    @Transactional
    public CarouselSlide saveSlide(CarouselSlide slide) {
        if (!mediaStorage.exists(slide.objectKey())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA, "找不到已上傳的圖片，請重新上傳");
        }
        CarouselSlide saved = layoutRepository.saveSlide(slide);
        log.info("輪播圖已儲存 id={}, key={}", saved.id(), saved.objectKey());
        return saved;
    }

    @Override
    @Transactional
    public void deleteSlide(Long slideId) {
        // 只刪關聯不刪物件，與商品圖同一個判準（ADR-0027 決策 5）：孤兒只花錢，破圖砸在客人臉上
        layoutRepository.deleteSlide(slideId);
        log.info("輪播圖已刪除 id={}（物件保留，交由對帳處理）", slideId);
    }
}
