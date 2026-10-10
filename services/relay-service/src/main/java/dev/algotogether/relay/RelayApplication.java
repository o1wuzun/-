package dev.algotogether.relay;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"dev.algotogether.relay","dev.algotogether.contracts"})
public class RelayApplication {public static void main(String[] args){SpringApplication.run(RelayApplication.class,args);}}
