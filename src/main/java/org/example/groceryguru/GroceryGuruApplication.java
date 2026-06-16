package org.example.groceryguru;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GroceryGuruApplication {

    public static void main(String[] args) {
        SpringApplication.run(GroceryGuruApplication.class, args);
    }

}
