package com.github.h3yow.modelservinggatewayjava.gateway.config;

import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.BackendType;
import com.github.h3yow.modelservinggatewayjava.gateway.backend.InMemoryInferenceBackend;
import com.github.h3yow.modelservinggatewayjava.gateway.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.gateway.resilience.CircuitBreakerRegistry;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.CanaryRouter;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ConfigEpoch;
import com.github.h3yow.modelservinggatewayjava.gateway.routing.ModelVersionSpec;
import com.github.h3yow.modelservinggatewayjava.gateway.service.ScoringService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关装配：基于 gateway.* 配置在内存中构建版本、后端、路由、熔断与缓存。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayBootstrap {

    @Bean
    public ConfigEpoch configEpoch() {
        return new ConfigEpoch();
    }

    @Bean
    public CanaryRouter canaryRouter(GatewayProperties props, ConfigEpoch epoch) {
        CanaryRouter router = new CanaryRouter(epoch);

        props.getVersions().forEach((version, v) ->
                router.registerVersion(new ModelVersionSpec(
                        version, v.getFamily(), v.getExpectedFeatureDim())));

        props.getWeights().forEach((family, w) -> {
            // registerVersion 已建立 family；这里直接应用配置权重（不触发额外 bump）
            applyInitialWeights(router, family, w);
        });
        return router;
    }

    private void applyInitialWeights(CanaryRouter router, String family,
                                     java.util.Map<String, Integer> w) {
        router.seedWeights(family, w);
    }

    @Bean
    public BackendRegistry backendRegistry(GatewayProperties props) {
        BackendRegistry registry = new BackendRegistry();
        for (GatewayProperties.BackendProps b : props.getBackends()) {
            registry.register(new InMemoryInferenceBackend(
                    b.getId(), BackendType.valueOf(b.getType()), b.getVersion()));
        }
        return registry;
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(GatewayProperties props) {
        return new CircuitBreakerRegistry(
                props.getCircuit().getFailureThreshold(),
                props.getCircuit().getOpenDurationMs());
    }

    @Bean
    public ScoringCache scoringCache(GatewayProperties props) {
        return new ScoringCache(props.getCache().getTtlMs());
    }

    @Bean
    public ScoringService scoringService(CanaryRouter router, BackendRegistry backends,
                                         CircuitBreakerRegistry circuits, ScoringCache cache,
                                         ConfigEpoch epoch) {
        return new ScoringService(router, backends, circuits, cache, epoch);
    }
}
