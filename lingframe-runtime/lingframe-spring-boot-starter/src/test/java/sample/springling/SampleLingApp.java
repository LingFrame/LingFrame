package sample.springling;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.ling.Ling;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@SpringBootApplication
public class SampleLingApp implements Ling {
    public static void main(String[] args) {
        SpringApplication.run(SampleLingApp.class, args);
    }

    @Override
    public void onStart(LingContext context) {
    }

    @Override
    public void onStop(LingContext context) {
    }

    @Bean
    public ExecutorService lingExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "sample-ling-executor");
            thread.setDaemon(true);
            return thread;
        });
    }

    @RestController
    public static class DemoController {
        @GetMapping("/demo/ping")
        public String ping() {
            log.info("[SampleLing] Received ping request");
            return "pong";
        }
    }
}
