package com.github.h3yow.modelservinggatewayjava.config;

import com.github.h3yow.modelservinggatewayjava.backend.InMemoryBackend;
import com.github.h3yow.modelservinggatewayjava.backend.InferenceBackend;
import com.github.h3yow.modelservinggatewayjava.cache.ScoringCache;
import com.github.h3yow.modelservinggatewayjava.circuit.CircuitBreaker;
import com.github.h3yow.modelservinggatewayjava.model.ModelVersion;
import com.github.h3yow.modelservinggatewayjava.routing.WeightedRouter;
import com.github.h3yow.modelservinggatewayjava.service.ScoringService;
import com.github.h3yow.modelservinggatewayjava.version.VersionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关 Bean 装配：内置内存后端、版本注册表、熔断器、缓存与评分服务。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    @Bean
    public Map<String, InferenceBackend> backends(GatewayProperties props) {
        Map<String, InferenceBackend> map = new LinkedHashMap<>();
        for (GatewayProperties.BackendProps b : props.getBackends()) {
            map.put(b.getId(), new InMemoryBackend(b.getId(), b.getLatencyMs(), b.getFailure()));
        }
        return map;
    }

    @Bean
    public Map<String, CircuitBreaker> circuitBreakers(GatewayProperties props) {
        Map<String, CircuitBreaker> map = new LinkedHashMap<>();
        for (GatewayProperties.BackendProps b : props.getBackends()) {
            map.put(b.getId(), new CircuitBreaker(props.getFailureThreshold(), props.getOpenStateMs()));
        }
        return map;
    }

    @Bean
    public VersionRegistry versionRegistry(GatewayProperties props) {
        List<ModelVersion> versions = props.getVersions().stream()
                .map(v -> new ModelVersion(v.getModel(), v.getVersion(), v.getBackend(),
                        v.isEnabled(), v.getWeight()))
                .toList();
        return new VersionRegistry(versions);
    }

    @Bean
    public WeightedRouter weightedRouter(GatewayProperties props) {
        return new WeightedRouter();
    }

    @Bean
    public ScoringCache scoringCache(GatewayProperties props) {
        return new ScoringCache(props.getCacheTtlMs());
    }

    @Bean
    public ScoringService scoringService(VersionRegistry registry,
                                         WeightedRouter router,
                                         Map<String, InferenceBackend> backends,
                                         Map<String, CircuitBreaker> breakers,
                                         ScoringCache cache,
                                         GatewayProperties props) {
        return new ScoringService(registry, router, backends, breakers, cache,
                props.getBackendTimeoutMs());
    }
}
