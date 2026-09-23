package com.github.h3yow.modelservinggatewayjava.web;

import com.github.h3yow.modelservinggatewayjava.exception.GatewayException;
import com.github.h3yow.modelservinggatewayjava.model.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将异常映射为稳定、可区分的对外结果：
 * <ul>
 *   <li>INVALID_REQUEST / 反序列化失败 -> HTTP 400 + reason=INVALID_REQUEST；</li>
 *   <li>版本/后端/熔断等业务失败不抛异常，由 ScoreResponse.failure 承载（HTTP 200）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGateway(GatewayException e) {
        HttpStatus status = e.getReason() == RejectReason.INVALID_REQUEST
                ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of(
                "success", false,
                "reason", e.getReason().name(),
                "message", e.getMessage() == null ? "" : e.getMessage()
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "reason", RejectReason.INVALID_REQUEST.name(),
                "message", "malformed request body"
        ));
    }
}
