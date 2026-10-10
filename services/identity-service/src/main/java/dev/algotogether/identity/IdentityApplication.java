package dev.algotogether.identity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"dev.algotogether.identity","dev.algotogether.contracts"})
public class IdentityApplication { public static void main(String[] args){SpringApplication.run(IdentityApplication.class,args);} }
