package dev.algotogether.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailQueueTest {
    EmbeddedDatabase source;JdbcTemplate db;RabbitTemplate rabbit;JavaMailSender smtp;MailQueue worker;String id;
    @BeforeEach void setup(){
        source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        db=new JdbcTemplate(source);rabbit=mock(RabbitTemplate.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));smtp=mock(JavaMailSender.class,withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));worker=new MailQueue(db,rabbit,smtp,"test@localhost");
        db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES('u','student@example.test','Student','unused')");
        db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at) VALUES('r','u','group','test','APPROVED','today')");
        new NotificationService(db).decision("r","APPROVED");
        id=db.queryForObject("SELECT id FROM mail_jobs",String.class);
        doAnswer(invocation->{CorrelationData c=invocation.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
    @AfterEach void close(){source.shutdown();}
    Message message(){return new Message(id.getBytes(StandardCharsets.UTF_8));}
    String status(){return db.queryForObject("SELECT status FROM mail_jobs WHERE id=?",String.class,id);}
    @Test void confirmedPublishAndDuplicateDelivery(){
        worker.publish();assertEquals("QUEUED",status());worker.consume(message());worker.consume(message());
        assertEquals("SENT",status());verify(smtp,times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void brokerOutageRetainsOutbox(){
        doThrow(new IllegalStateException("credential must not leak")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        worker.publish();assertEquals("RETRY",status());assertFalse(db.queryForObject("SELECT last_error FROM mail_jobs",String.class).contains("credential"));
        verifyNoInteractions(smtp);
    }
    @Test void returnedMessageIsNeverMarkedQueued(){
        doAnswer(inv->{CorrelationData c=inv.getArgument(3);c.setReturned(new ReturnedMessage(message(),312,"NO_ROUTE","exchange","route"));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        worker.publish();assertEquals("RETRY",status());
    }
    @Test void smtpFailuresBackoffAndExhaust(){
        doThrow(new MailSendException("password must not leak")).when(smtp).send(any(SimpleMailMessage.class));
        for(int i=1;i<=5;i++){
            db.update("UPDATE mail_jobs SET next_attempt_at=0");worker.publish();worker.consume(message());
            assertEquals(i==5?"FAILED":"RETRY",status());
            assertEquals(i,db.queryForObject("SELECT attempts FROM mail_jobs",Integer.class));
        }
        worker.publish();verify(smtp,times(5)).send(any(SimpleMailMessage.class));
    }
    @Test void missingConfigDoesNotPretendToSend(){
        worker=new MailQueue(db,rabbit,smtp,"");worker.publish();worker.consume(message());
        assertEquals("CONFIG_REQUIRED",status());assertEquals(0,db.queryForObject("SELECT attempts FROM mail_jobs",Integer.class));verifyNoInteractions(smtp);
    }
    @Test void consumerCanWinPublishAcknowledgementRace(){
        doAnswer(inv->{worker.consume(inv.getArgument(2));CorrelationData c=inv.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        worker.publish();assertEquals("SENT",status());
    }
    @Test void abandonedLeaseRecoveredAndForeignMessageIgnored(){
        worker.consume(new Message(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));verifyNoInteractions(smtp);
        db.update("UPDATE mail_jobs SET status='SENDING',next_attempt_at=0");worker.publish();worker.consume(message());assertEquals("SENT",status());
    }
}
