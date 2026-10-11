package dev.algotogether.identity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@org.springframework.context.annotation.Import(dev.algotogether.persistence.PersistenceConfiguration.class)
@SpringBootApplication(scanBasePackages={"dev.algotogether.identity","dev.algotogether.contracts"})
public class IdentityApplication { public static void main(String[] args){SpringApplication.run(IdentityApplication.class,args);} }
