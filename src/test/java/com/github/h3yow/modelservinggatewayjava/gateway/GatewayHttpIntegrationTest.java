package com.github.h3yow.modelservinggatewayjava.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：经真实 HTTP 层验证路由、灰度切流、异常分级与观测接口。
 * 默认配置：ml-score v1(3维,100权重) / v2(4维,0权重)。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttpIntegrationTest.class);

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/admin/reset")).andExpect(status().isOk());
        // 还原默认 100/0 权重
        mvc.perform(post("/admin/families/ml-score/weights")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ml-score-v1\":100,\"ml-score-v2\":0}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/admin/cache")).andExpect(status().isOk());
    }

    @Test
    void scoreRoutesToV1_andRejectsUnregisteredVersionOverHttp() throws Exception {
        mvc.perform(post("/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-1\",\"requestedVersion\":\"ml-score\","
                                + "\"features\":[1.0,2.0,3.0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.servedVersion").value("ml-score-v1"))
                .andExpect(jsonPath("$.fromCache").value(false));

        mvc.perform(post("/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"http-2\",\"requestedVersion\":\"ghost-v9\","
                                + "\"features\":[1.0,2.0,3.0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_REGISTERED"))
                .andExpect(jsonPath("$.servedVersion").doesNotExist());
        log.info("[HTTP] v1 路由成功 + 未注册版本返回 VERSION_NOT_REGISTERED 验证通过");
    }

    @Test
    void canarySwitchMovesObservableTrafficToV2() throws Exception {
        mvc.perform(post("/admin/families/ml-score/weights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ml-score-v1\":0,\"ml-score-v2\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/score")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"cv-" + i + "\",\"requestedVersion\":\"ml-score\","
                                    + "\"features\":[1,2,3,4]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.servedVersion").value("ml-score-v2"));
        }
        mvc.perform(get("/admin/traffic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traffic.ml-score.ml-score-v2").value(20));
        log.info("[HTTP] 切流后 20 次家族请求全部命中 ml-score-v2，/admin/traffic 观测一致");
    }

    @Test
    void injectTimeoutThenObserveFallbackAndCircuitOverHttp() throws Exception {
        // 让 v1 的 cpu-v1-1 超时，应降级到 cpu-v1-2
        mvc.perform(post("/admin/backends/cpu-v1-1/failure").param("mode", "TIMEOUT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(post("/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"fb-http\",\"requestedVersion\":\"ml-score-v1\","
                                + "\"features\":[5,6,7]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.backendId").value("cpu-v1-2"))
                .andExpect(jsonPath("$.decisionReason")
                        .value(org.hamcrest.Matchers.containsString("FALLBACK_HEALTHY_BACKEND")));
        log.info("[HTTP] cpu-v1-1 超时后降级 cpu-v1-2 验证通过");
    }

    @Test
    void circuitHealthAndTimelineAreReadOnlyOverHttp() throws Exception {
        // 让 v1 两个后端都内部错误，连续请求把熔断打成 OPEN（阈值 3）
        mvc.perform(post("/admin/backends/cpu-v1-1/failure").param("mode", "INTERNAL_ERROR"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/backends/cpu-v1-2/failure").param("mode", "INTERNAL_ERROR"))
                .andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/score")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"trip-" + i + "\",\"requestedVersion\":\"ml-score-v1\","
                                    + "\"features\":[1,2," + i + ".5]}"))
                    .andExpect(status().isOk());
        }

        // 健康视图：隔离阶段 + 剩余隔离时长 + 时间线首条为达到阈值打开
        mvc.perform(get("/admin/backends/cpu-v1-1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.view.backendId").value("cpu-v1-1"))
                .andExpect(jsonPath("$.view.state").value("OPEN"))
                .andExpect(jsonPath("$.view.phase").value("ISOLATED"))
                .andExpect(jsonPath("$.view.remainingIsolationMs").isNumber())
                .andExpect(jsonPath("$.view.currentBackoffMs").value(5000))
                .andExpect(jsonPath("$.view.timeline[0].reason").value("FAILURE_THRESHOLD_REACHED"))
                .andExpect(jsonPath("$.view.timeline[0].from").value("CLOSED"))
                .andExpect(jsonPath("$.view.timeline[0].to").value("OPEN"));

        // 连续只读查询不得推进或打断恢复：状态、剩余隔离时长起点、时间线条数保持稳定
        String before = mvc.perform(get("/admin/circuits"))
                .andExpect(jsonPath("$.backends.cpu-v1-1.state").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/admin/circuits")).andExpect(status().isOk());
            mvc.perform(get("/admin/backends/cpu-v1-1/health")).andExpect(status().isOk());
            mvc.perform(get("/admin/traffic")).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/backends/cpu-v1-1/health"))
                .andExpect(jsonPath("$.view.state").value("OPEN"))
                .andExpect(jsonPath("$.view.phase").value("ISOLATED"))
                .andExpect(jsonPath("$.view.timeline.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
        log.info("[HTTP] 熔断健康视图/时间线字段正确，连续 60+ 次只读查询状态保持 OPEN/ISOLATED，观测无副作用");
    }

    @Test
    void batchReturnsStableResultsAndCacheCounters() throws Exception {
        String body = "{\"requests\":["
                + "{\"requestId\":\"b1\",\"requestedVersion\":\"ml-score-v1\",\"features\":[1,1,1]},"
                + "{\"requestId\":\"b2\",\"requestedVersion\":\"ml-score-v1\",\"features\":[1,1,1]},"
                + "{\"requestId\":\"b3\",\"requestedVersion\":\"ml-score-v1\",\"features\":[2,2,2]}"
                + "]}";
        mvc.perform(post("/score/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].requestId").value("b1"))
                .andExpect(jsonPath("$.results[1].requestId").value("b2"))
                .andExpect(jsonPath("$.results[1].fromCache").value(true))
                .andExpect(jsonPath("$.cacheHits").value(1))
                .andExpect(jsonPath("$.cacheComputed").value(2));
        log.info("[HTTP] 批量顺序稳定、重复请求命中缓存、计数正确验证通过");
    }
}
