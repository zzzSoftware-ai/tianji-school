package com.tianji.remark.task;


import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LikedTimesCheckTask {

    private static final List<String> BIZ_TYOES =List.of("QA","NOTE");
    private static final int MAX_BIZ_SIZE = 30;
    private final ILikedRecordService recordService;

    @Scheduled(fixedDelay = 20000)
    public void checkLikedTimes(){

        for (String bizTyoe : BIZ_TYOES) {
            recordService.readLikedTimesAndSendMessage(bizTyoe,MAX_BIZ_SIZE);
        }

    }

}
