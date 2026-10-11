package dev.algotogether.replica;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@org.springframework.context.annotation.Import(dev.algotogether.persistence.PersistenceConfiguration.class)
@SpringBootApplication(scanBasePackages={"dev.algotogether.replica","dev.algotogether.contracts"}) @EnableScheduling
public class ReplicaApplication {public static void main(String[] args){SpringApplication.run(ReplicaApplication.class,args);}}
