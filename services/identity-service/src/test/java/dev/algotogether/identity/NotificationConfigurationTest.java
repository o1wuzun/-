package dev.algotogether.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.rabbitmq.listener.simple.auto-startup=false","notifications.poll-ms=3600000"})
@ActiveProfiles("notifications")
class NotificationConfigurationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("identity.public-key",()->IdentityIntegrationTest.pub);r.add("identity.private-key",()->IdentityIntegrationTest.priv);r.add("spring.datasource.url",()->"jdbc:h2:mem:notification-config-test;DB_CLOSE_DELAY=-1");}
    @Autowired Queue durableMailQueue;
    @Autowired CachingConnectionFactory connection;
    @Test void enabledConfigurationStartsAndRequiresPublisherConfirmation(){
        assertTrue(durableMailQueue.isDurable());assertFalse(durableMailQueue.isAutoDelete());
        assertTrue(connection.isPublisherConfirms());assertTrue(connection.isPublisherReturns());
    }
}
