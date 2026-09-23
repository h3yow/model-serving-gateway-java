package com.github.h3yow.modelservinggatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 端到端：接口映射、可区分原因、灰度观测与管理接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void score_pinnedVersion_ok() throws Exception {
        mockMvc.perform(post("/api/score")
                        .contentType("application/json")
                        .content("""
                                {"modelName":"fraud","modelVersion":"v1","featureHash":"api-1","features":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.modelVersion").value("v1"))
                .andExpect(jsonPath("$.backendId").value("backend-a"));
    }

    @Test
    void score_missingFields_returnsInvalidRequest400() throws Exception {
        mockMvc.perform(post("/api/score")
                        .contentType("application/json")
                        .content("""
                                {"modelName":"fraud","features":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
    }

    @Test
    void score_unregisteredVersion_returnsDistinctReason() throws Exception {
        mockMvc.perform(post("/api/score")
                        .contentType("application/json")
                        .content("""
                                {"modelName":"fraud","modelVersion":"vX","featureHash":"api-2","features":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("VERSION_NOT_REGISTERED"))
                .andExpect(jsonPath("$.backendId").doesNotExist());
    }

    @Test
    void stats_exposesTrafficAndBreakerState() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traffic").isString())
                .andExpect(jsonPath("$.circuitBreakers.backend-a").isString())
                .andExpect(jsonPath("$.configVersion").isNumber());
    }

    @Test
    void admin_weightChange_takesEffect() throws Exception {
        mockMvc.perform(post("/api/admin/weight")
                        .param("model", "fraud")
                        .param("version", "v1")
                        .param("weight", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        // v1 权重为 0 后，指定 v1 的请求应以 VERSION_INCOMPATIBLE 拒绝
        try {
            mockMvc.perform(post("/api/score")
                            .contentType("application/json")
                            .content("""
                                    {"modelName":"fraud","modelVersion":"v1","featureHash":"api-3","features":{}}
                                    """))
                    .andExpect(jsonPath("$.reason").value("VERSION_INCOMPATIBLE"));
        } finally {
            // 还原配置，避免影响其他测试
            mockMvc.perform(post("/api/admin/weight")
                    .param("model", "fraud").param("version", "v1").param("weight", "80"));
        }
    }
}
