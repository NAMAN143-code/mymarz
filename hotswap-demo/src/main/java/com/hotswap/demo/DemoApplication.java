package com.hotswap.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HotSwap Demo Application.
 *
 * Run this app, then edit config/hotswap-demo.yml while it's running.
 * Hit the REST endpoints to see values change in real-time — no restart needed.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
