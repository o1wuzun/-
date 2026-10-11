package dev.algotogether.identity;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Application-internal notification delivery. It does not send SMTP mail. */
@Configuration
@ConditionalOnProperty(name="notifications.enabled",havingValue="true")
public class InAppNotificationQueue {
    static final String EXCHANGE="community.inapp.v1", QUEUE="community.inapp.notifications.v1", ROUTING="notification";
    private final JdbcTemplate db; private final RabbitTemplate rabbit;
    public InAppNotificationQueue(JdbcTemplate db,RabbitTemplate rabbit){this.db=db;this.rabbit=rabbit;}
    @Bean DirectExchange inAppExchange(){return new DirectExchange(EXCHANGE,true,false);}
    @Bean Queue inAppQueue(){return new Queue(QUEUE,true);}
    @Bean Binding inAppBinding(Queue inAppQueue,DirectExchange inAppExchange){return BindingBuilder.bind(inAppQueue).to(inAppExchange).with(ROUTING);}

    @Scheduled(fixedDelayString="${notifications.inapp-poll-ms:1000}")
    public void publish(){
        var rows=db.queryForList("SELECT id FROM notifications WHERE delivered_at IS NULL ORDER BY created_at LIMIT 50");
        for(var row:rows){String id=(String)row.get("ID");
            if(db.update("UPDATE notifications SET delivered_at='PUBLISHING' WHERE id=? AND delivered_at IS NULL",id)==0)continue;
            try{var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(id);var c=new CorrelationData("inapp-"+id);rabbit.send(EXCHANGE,ROUTING,new Message(id.getBytes(StandardCharsets.UTF_8),props),c);var confirm=c.getFuture().get(5,TimeUnit.SECONDS);if(!confirm.isAck())throw new IllegalStateException();db.update("UPDATE notifications SET delivered_at='QUEUED' WHERE id=? AND delivered_at='PUBLISHING'",id);}
            catch(Exception e){db.update("UPDATE notifications SET delivered_at=NULL WHERE id=? AND delivered_at='PUBLISHING'",id);break;}
        }
    }
    @RabbitListener(queues=QUEUE)
    public void consume(Message message){String id=new String(message.getBody(),StandardCharsets.UTF_8);db.update("UPDATE notifications SET delivered_at='DELIVERED' WHERE id=? AND delivered_at IN ('PUBLISHING','QUEUED')",id);}
}
