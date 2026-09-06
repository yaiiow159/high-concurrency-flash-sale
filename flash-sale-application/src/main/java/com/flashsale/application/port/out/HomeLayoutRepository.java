package com.flashsale.application.port.out;

import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;

import java.util.List;
import java.util.Optional;

/** 首頁版型的持久化埠（出站）。 */
public interface HomeLayoutRepository {

    /** 全部版位，依排序。前台自己過濾上架期間，後台要看得到停用的。 */
    List<HomeSection> findAllSections();

    Optional<HomeSection> findSection(Long sectionId);

    HomeSection saveSection(HomeSection section);

    void deleteSection(Long sectionId);

    List<CarouselSlide> findAllSlides();

    CarouselSlide saveSlide(CarouselSlide slide);

    void deleteSlide(Long slideId);
}
