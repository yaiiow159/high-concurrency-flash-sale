package com.flashsale.domain.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * 風險評分。分數是加總而不是任一命中就拒絕：單一訊號都有無辜的解釋
 * （宿舍共用 IP、剛註冊就來搶），疊在一起才像機器人。
 */
public record RiskAssessment(int score, List<String> reasons, boolean rejected) {

    public static RiskAssessment evaluate(RiskSignals signals, RiskPolicy policy) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (signals.accountAgeSeconds() < policy.youngAccountSeconds()) {
            score += 30;
            reasons.add("新註冊帳號");
        }
        if (signals.usersOnSameIp() > policy.maxUsersPerIp()) {
            score += 40;
            reasons.add("同一 IP 多個帳號");
        }
        if (signals.usersOnSameDevice() > policy.maxUsersPerDevice()) {
            score += 40;
            reasons.add("同一裝置多個帳號");
        }
        if (signals.recentQualificationsByUser() > policy.maxQualificationsPerUser()) {
            score += 20;
            reasons.add("短時間反覆領取資格");
        }
        return new RiskAssessment(score, List.copyOf(reasons), score >= policy.rejectScore());
    }
}
