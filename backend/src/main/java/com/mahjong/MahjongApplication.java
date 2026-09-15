package com.mahjong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MahjongApplication {
    public static void main(String[] args) { SpringApplication.run(MahjongApplication.class, args); }
}
