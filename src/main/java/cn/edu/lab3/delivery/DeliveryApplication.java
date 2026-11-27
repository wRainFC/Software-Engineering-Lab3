package cn.edu.lab3.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the food ordering and delivery application. */
@SpringBootApplication
public class DeliveryApplication {

  /**
   * Starts the local web application.
   *
   * @param args command-line arguments passed to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(DeliveryApplication.class, args);
  }
}
