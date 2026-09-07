package com.flashsale.application.port.out;

import com.flashsale.domain.risk.QualificationToken;

import java.util.Optional;

/**
 * 資格憑證的簽發與驗證。實作必須保證：驗證是純 CPU、零遠端呼叫——
 * 它跑在秒殺熱路徑上；簽章被竄改或格式不對時回 empty，不拋例外。
 */
public interface QualificationTokenCodec {

    String issue(QualificationToken token);

    Optional<QualificationToken> verify(String encoded);
}
