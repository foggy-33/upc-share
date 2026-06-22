package cn.upcshare.downloadsite.support;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException e) {
        String message = "上传文件过大，单文件最大支持 1GB";
        return ResponseEntity.status(413).body(Map.of("ok", false, "msg", message, "detail", message));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("ok", false, "msg", e.getMessage(), "detail", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> general(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of(
                "ok", false,
                "msg", "服务器内部错误",
                "detail", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())
        ));
    }
}
