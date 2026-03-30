package tgbotgpt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TgBotGptApplication {

    public static void main(String[] args) {
        SpringApplication.run(TgBotGptApplication.class, args);
    }

}
