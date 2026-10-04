package com.example.seats;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatApplication {
  public static void main(String[] args) { SpringApplication.run(SeatApplication.class, args); }
}
