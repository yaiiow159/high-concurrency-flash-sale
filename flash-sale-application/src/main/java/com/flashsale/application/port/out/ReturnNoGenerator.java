package com.flashsale.application.port.out;

import com.flashsale.domain.aftersales.ReturnNo;

/** 退貨單號產生器（出站）。 */
public interface ReturnNoGenerator {

    ReturnNo next();
}
