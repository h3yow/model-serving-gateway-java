package com.github.h3yow.modelservinggatewayjava.gateway.web;

import com.github.h3yow.modelservinggatewayjava.gateway.model.BatchScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.BatchScoreResponse;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.gateway.model.ScoreResult;
import com.github.h3yow.modelservinggatewayjava.gateway.service.ScoringService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评分入口。失败也以 200 + 结构化 {@link ScoreResult} 返回，
 * 通过稳定 errorCode 区分失败原因，避免调用方误解析。
 */
@RestController
public class ScoreController {

    private final ScoringService scoringService;

    public ScoreController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @PostMapping("/score")
    public ScoreResult score(@RequestBody ScoreRequest request) {
        return scoringService.score(request);
    }

    @PostMapping("/score/batch")
    public BatchScoreResponse batch(@RequestBody BatchScoreRequest request) {
        return scoringService.batch(request == null ? null : request.requests());
    }
}
