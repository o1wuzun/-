package dev.algotogether.tunnel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication(scanBasePackages={"dev.algotogether.tunnel","dev.algotogether.contracts"}) @EnableScheduling
public class TunnelApplication {public static void main(String[] args){SpringApplication.run(TunnelApplication.class,args);}}
