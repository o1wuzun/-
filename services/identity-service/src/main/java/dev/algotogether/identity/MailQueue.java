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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="notifications.enabled",havingValue="true")
public class MailQueue {
    static final String QUEUE="community.mail.v1", EXCHANGE="community.notifications.v1", ROUTING="mail";
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final JavaMailSender mail;
    private final String sender;
    public MailQueue(JdbcTemplate db,RabbitTemplate rabbit,JavaMailSender mail,@Value("${notifications.from:}")String sender) {
        this.db=db;this.rabbit=rabbit;this.mail=mail;this.sender=sender;
    }
    @Bean Queue durableMailQueue(){return new Queue(QUEUE,true);}
    @Bean DirectExchange notificationExchange(){return new DirectExchange(EXCHANGE,true,false);}
    @Bean Binding mailBinding(Queue durableMailQueue,DirectExchange notificationExchange){return BindingBuilder.bind(durableMailQueue).to(notificationExchange).with(ROUTING);}

    @Scheduled(fixedDelayString="${notifications.poll-ms:3000}")
    public void publish() {
        long now=System.currentTimeMillis();
        // Recover abandoned sends and confirmed messages lost before the DB acknowledgement.
        db.update("UPDATE mail_jobs SET status='RETRY',next_attempt_at=? WHERE status IN ('SENDING','PUBLISHING','QUEUED') AND next_attempt_at<=?",now,now);
        var jobs=db.queryForList("SELECT id FROM mail_jobs WHERE status IN ('PENDING','RETRY','CONFIG_REQUIRED') AND next_attempt_at<=? ORDER BY created_at LIMIT 20",now);
        for(var job:jobs){
            String id=(String)job.get("ID");
            if(db.update("UPDATE mail_jobs SET status='PUBLISHING',next_attempt_at=?,publish_attempts=publish_attempts+1 WHERE id=? AND status IN ('PENDING','RETRY','CONFIG_REQUIRED') AND next_attempt_at<=?",now+300000,id,now)==0)continue;
            try {
                var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(id);props.setContentType("text/plain");
                var correlation=new CorrelationData(id);
                rabbit.setMandatory(true);
                rabbit.send(EXCHANGE,ROUTING,new Message(id.getBytes(StandardCharsets.UTF_8),props),correlation);
                var confirm=correlation.getFuture().get(10,TimeUnit.SECONDS);
                if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Unconfirmed publish");
                db.update("UPDATE mail_jobs SET status='QUEUED',last_error=NULL WHERE id=? AND status='PUBLISHING'",id);
            } catch(Exception e){
                // Never expose SMTP/broker exception strings that may contain credentials.
                db.update("UPDATE mail_jobs SET status='RETRY',next_attempt_at=?,last_error='消息队列暂不可用，稍后自动重试' WHERE id=? AND status='PUBLISHING'",System.currentTimeMillis()+30000,id);
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                break;
            }
        }
    }
    @RabbitListener(queues=QUEUE)
    public void consume(Message message) {
        String id=new String(message.getBody(),StandardCharsets.UTF_8);
        if(!id.matches("[a-f0-9-]{36}"))return;
        long now=System.currentTimeMillis();
        if(db.update("UPDATE mail_jobs SET status='SENDING',next_attempt_at=? WHERE id=? AND status IN ('PUBLISHING','QUEUED')",now+300000,id)==0)return;
        var job=db.queryForMap("SELECT recipient,subject,body,attempts FROM mail_jobs WHERE id=?",id);
        if(sender.isBlank()){
            db.update("UPDATE mail_jobs SET status='CONFIG_REQUIRED',next_attempt_at=?,last_error='请配置 SMTP 发件地址' WHERE id=?",now+60000,id);return;
        }
        int attempts=((Number)job.get("ATTEMPTS")).intValue()+1;
        try {
            var email=new SimpleMailMessage();email.setFrom(sender);email.setTo((String)job.get("RECIPIENT"));
            email.setSubject((String)job.get("SUBJECT"));email.setText((String)job.get("BODY"));
            mail.send(email);
        }catch(Exception e){
            db.update("UPDATE mail_jobs SET status=?,attempts=?,next_attempt_at=?,last_error='邮件发送失败，请检查 SMTP 配置或服务' WHERE id=?",
                    attempts>=5?"FAILED":"RETRY",attempts,now+Math.min(3600000L,30000L*(1L<<attempts)),id);return;
        }
        // DB failure after SMTP acceptance propagates, preserving recovery instead of hiding it.
        db.update("UPDATE mail_jobs SET status='SENT',attempts=?,sent_at=?,last_error=NULL WHERE id=?",attempts,java.time.Instant.now().toString(),id);
    }
}
