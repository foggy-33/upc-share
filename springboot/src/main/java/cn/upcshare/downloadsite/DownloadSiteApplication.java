package cn.upcshare.downloadsite;

import cn.upcshare.downloadsite.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DownloadSiteApplication {
    public static void main(String[] args) {
        SpringApplication.run(DownloadSiteApplication.class, args);
    }
}
