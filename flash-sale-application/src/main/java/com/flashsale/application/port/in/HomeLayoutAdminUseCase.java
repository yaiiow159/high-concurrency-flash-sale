package com.flashsale.application.port.in;

import com.flashsale.domain.home.CarouselSlide;
import com.flashsale.domain.home.HomeSection;

import java.util.List;

/** 後台管理首頁版型。 */
public interface HomeLayoutAdminUseCase {

    /** 全部版位，含停用與不在期間內的——後台要看得到才能改。 */
    List<HomeSection> allSections();

    HomeSection saveSection(HomeSection section);

    void deleteSection(Long sectionId);

    List<CarouselSlide> allSlides();

    CarouselSlide saveSlide(CarouselSlide slide);

    void deleteSlide(Long slideId);
}
