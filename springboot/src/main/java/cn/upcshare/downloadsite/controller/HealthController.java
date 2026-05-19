package cn.upcshare.downloadsite.controller;

import cn.upcshare.downloadsite.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final AppProperties props;

    public HealthController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/ping")
    Map<String, Object> ping() {
        return Map.of("ok", true, "node", props.getNodeName(), "server_time", Instant.now().toString());
    }
}
