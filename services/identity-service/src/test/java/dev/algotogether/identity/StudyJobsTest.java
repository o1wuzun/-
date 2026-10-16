package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.algotogether.contracts.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class StudyJobsTest {
 @Test void asyncChecksArePrivateScopedRetryableAndNeverPersistBearerTokens()throws Exception{
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();var db=new JdbcTemplate(source);
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var payload=new AtomicReference<String>();var available=new AtomicBoolean(true);
  String auth="Bearer "+new TokenCodec(IdentityIntegrationTest.pub,IdentityIntegrationTest.priv).issue("student");String other="Bearer "+new TokenCodec(IdentityIntegrationTest.pub,IdentityIntegrationTest.priv).issue("other");
  server.createContext("/api/v1/group/study",e->{byte[] response="{\"data\":{\"task\":{\"TITLE\":\"ONLY-GROUP-A\",\"DRAFT\":\"DO-NOT-SEND-DRAFT\",\"DRAFT_EVIDENCE\":\"DO-NOT-SEND-PROOF-DRAFT\"},\"attempts\":[{\"ID\":\"attempt\",\"SUBMISSION_ID\":\"submission\",\"ANSWER\":\"my answer\"}],\"submissions\":[{\"ID\":\"submission\",\"BODY\":\"my summary\"}]}}".getBytes();e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});
  server.createContext("/model",e->{payload.set(new String(e.getRequestBody().readAllBytes()));entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}byte[] response="{\"choices\":[{\"message\":{\"content\":\"仅对此任务的反馈\"}}]}".getBytes();e.sendResponseHeaders(available.get()?200:503,response.length);e.getResponseBody().write(response);e.close();});server.start();
  StudyJobsController jobs=null;
  try{
   String base="http://127.0.0.1:"+server.getAddress().getPort();db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES('student','s@t.test','s','unused'),('other','o@t.test','o','unused')");db.update("INSERT INTO user_settings(user_id,advisor_provider,advisor_api_key,advisor_endpoint) VALUES('student','fixture','model-secret',?)",base+"/model");
   jobs=new StudyJobsController(db,new ObjectMapper(),new GroupServiceLocator(db,base,base),IdentityIntegrationTest.pub,base+"/model");jobs.recover();
   var request=new StudyJobsController.Job("group-default","task","attempt","AI",true);String job=(String)((Map<?,?>)jobs.create(auth,request).data()).get("id");assertTrue(entered.await(3,TimeUnit.SECONDS));
   var controller=jobs;assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.create(auth,request)).getStatusCode().value());
   assertTrue(((List<?>)jobs.list(other,"group-default").data()).isEmpty());release.countDown();
   awaitStatus(db,job,"SUCCEEDED");assertTrue(payload.get().contains("ONLY-GROUP-A"));assertFalse(payload.get().contains("DO-NOT-SEND-DRAFT"));assertFalse(payload.get().contains("DO-NOT-SEND-PROOF-DRAFT"));assertFalse(payload.get().contains("model-secret"));
   assertFalse(new ObjectMapper().writeValueAsString(jobs.list(auth,"group-default").data()).contains(auth));
   assertTrue(((List<?>)jobs.list(auth,"group-contest").data()).isEmpty());
   jobs.appeal(auth,job,new StudyJobsController.Appeal("请人工复核"));assertEquals("请人工复核",db.queryForObject("SELECT appeal FROM study_jobs WHERE id=?",String.class,job));
   available.set(false);String retry=(String)((Map<?,?>)jobs.create(auth,request).data()).get("id");awaitStatus(db,retry,"UNAVAILABLE");
   assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM study_jobs WHERE id=?",String.class,job));
   db.update("INSERT INTO study_jobs VALUES('interrupted','student','group-default','task','attempt','AI','PROCESSING','','','now','now')");jobs.recover();assertEquals("UNAVAILABLE",db.queryForObject("SELECT status FROM study_jobs WHERE id='interrupted'",String.class));
  }finally{release.countDown();if(jobs!=null)jobs.close();server.stop(0);executor.shutdownNow();source.shutdown();}
 }
 static void awaitStatus(JdbcTemplate db,String id,String expected){assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(!expected.equals(db.queryForObject("SELECT status FROM study_jobs WHERE id=?",String.class,id)))Thread.sleep(20);});}
}
