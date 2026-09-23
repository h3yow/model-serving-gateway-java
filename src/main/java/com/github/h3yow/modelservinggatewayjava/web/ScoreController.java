package com.github.h3yow.modelservinggatewayjava.web;

import com.github.h3yow.modelservinggatewayjava.model.ScoreRequest;
import com.github.h3yow.modelservinggatewayjava.model.ScoreResponse;
import com.github.h3yow.modelservinggatewayjava.routing.WeightedRouter;
import com.github.h3yow.modelservinggatewayjava.service.ScoringService;
import com.github.h3yow.modelservinggatewayjava.version.VersionRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评分 HTTP 接口。
 *
 * <ul>
 *   <li>POST /api/score        单条评分</li>
 *   <li>POST /api/score/batch  批量评分（带缓存 + single-flight 去重）</li>
 *   <li>GET  /api/stats        实际灰度流量分布、熔断状态、缓存命中观测</li>
 *   <li>POST /api/admin/weight 运行时调整灰度权重（触发缓存失效）</li>
 *   <li>POST /api/admin/enabled 版本上下线</li>
 *   <li>POST /api/cache/invalidate 手动清缓存</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ScoreController {

    private final ScoringService scoringService;
    private final VersionRegistry registry;
    private final WeightedRouter router;

    public ScoreController(ScoringService scoringService,
                           VersionRegistry registry,
                           WeightedRouter router) {
        this.scoringService = scoringService;
        this.registry = registry;
        this.router = router;
    }

    @PostMapping("/score")
    public ScoreResponse score(@RequestBody ScoreRequest request) {
        return scoringService.score(request);
    }

    @PostMapping("/score/batch")
    public List<ScoreResponse> scoreBatch(@RequestBody List<ScoreRequest> requests) {
        return scoringService.scoreBatch(requests, 8);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return scoringService.stats();
    }

    @PostMapping("/admin/weight")
    public Map<String, String> updateWeight(@RequestParam String model,
                                            @RequestParam String version,
                                            @RequestParam int weight) {
        registry.updateWeight(model, version, weight);
        // configVersion 已递增，缓存条目自带守卫；显式清空保证立即释放
        scoringService.invalidateCache();
        return Map.of("status", "ok", "traffic", router.trafficSnapshot());
    }

    @PostMapping("/admin/enabled")
    public Map<String, String> setEnabled(@RequestParam String model,
                                          @RequestParam String version,
                                          @RequestParam boolean enabled) {
        registry.setEnabled(model, version, enabled);
        scoringService.invalidateCache();
        return Map.of("status", "ok");
    }

    @PostMapping("/cache/invalidate")
    public Map<String, String> invalidateCache() {
        scoringService.invalidateCache();
        return Map.of("status", "ok");
    }
}
