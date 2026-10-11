package dev.algotogether.group;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@org.springframework.context.annotation.Import(dev.algotogether.persistence.PersistenceConfiguration.class)
@SpringBootApplication(scanBasePackages={"dev.algotogether.group","dev.algotogether.contracts"})
public class GroupApplication {public static void main(String[] args){SpringApplication.run(GroupApplication.class,args);}}
