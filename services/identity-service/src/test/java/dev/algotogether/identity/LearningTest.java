package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.algotogether.contracts.TokenCodec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LearningTest {
 @Test void boundOjEvidenceAndIdempotentSettlementAndPrivatePlanning() throws Exception {
  var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
  String pub=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),priv=Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
  var tokens=new TokenCodec(pub,priv);String ticket="Bearer "+tokens.issue("student"),other="Bearer "+tokens.issue("other");
  var database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();var db=new JdbcTemplate(database);var json=new ObjectMapper();
  db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES('student','student@example.com','student','unused'),('other','other@example.com','other','unused')");
  var payload=new AtomicReference<String>();var cf=new AtomicReference<String>();var status=new AtomicInteger(200);var received=new AtomicReference<String>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/user.status",exchange->{received.set(exchange.getRequestURI().getQuery());byte[] body=cf.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/model",exchange->{payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body="{\"choices\":[{\"message\":{\"content\":\"明日复习二分边界，先解释不变量再练习\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try {
   String base="http://127.0.0.1:"+server.getAddress().getPort();var learning=new LearningController(db,json,new DataSourceTransactionManager(database),pub,base);
   assertEquals(401,assertThrows(ResponseStatusException.class,()->learning.overview(null)).getStatusCode().value());
   learning.bind(ticket,new LearningController.Binding("Codeforces","student"));
   String point=(String)((Map<?,?>)learning.point(ticket,new LearningController.Point("二分不变量","4A","说明复杂度和边界")).data()).get("id");
   assertEquals(404,assertThrows(ResponseStatusException.class,()->learning.checkin(other,new LearningController.Checkin(point,30,"偷用目标"))).getStatusCode().value());
   String id=(String)((Map<?,?>)learning.checkin(ticket,new LearningController.Checkin(point,30,"左边界仍有疑问")).data()).get("id");
   assertEquals(409,assertThrows(ResponseStatusException.class,()->learning.checkin(ticket,new LearningController.Checkin(point,30,"重复"))).getStatusCode().value());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->learning.verify(other,id)).getStatusCode().value());
   assertEquals(409,assertThrows(ResponseStatusException.class,()->learning.settle(ticket,id)).getStatusCode().value());
   long now=Instant.now().getEpochSecond();
   cf.set(json.writeValueAsString(Map.of("status","OK","result",List.of(submission(1,now,"other","OK"),submission(2,now-86400,"student","OK"),submission(3,now,"student","WRONG_ANSWER")))));
   learning.verify(ticket,id);assertEquals("NO_MATCH",db.queryForObject("SELECT verification FROM daily_checkins WHERE id=?",String.class,id));
   assertTrue(received.get().contains("handle=student"));
   var truncated=new ArrayList<Map<String,Object>>();for(int i=0;i<1000;i++)truncated.add(submission(i,now,"student","WRONG_ANSWER"));
   cf.set(json.writeValueAsString(Map.of("status","OK","result",truncated)));
   assertEquals(409,assertThrows(ResponseStatusException.class,()->learning.verify(ticket,id)).getStatusCode().value());
   cf.set(json.writeValueAsString(Map.of("status","OK","result",List.of(submission(4,now,"student","OK")))));
   learning.verify(ticket,id);learning.bind(ticket,new LearningController.Binding("Codeforces","student"));
   assertEquals("PENDING",db.queryForObject("SELECT verification FROM daily_checkins WHERE id=?",String.class,id));
   status.set(503);assertEquals(502,assertThrows(ResponseStatusException.class,()->learning.verify(ticket,id)).getStatusCode().value());
   assertEquals("PENDING",db.queryForObject("SELECT verification FROM daily_checkins WHERE id=?",String.class,id));status.set(200);learning.verify(ticket,id);
   var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->learning.settle(ticket,id));var b=pool.submit(()->learning.settle(ticket,id));a.get();b.get();}finally{pool.shutdownNow();}
   assertEquals(3,db.queryForObject("SELECT score FROM daily_checkins WHERE id=?",Integer.class,id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_awards",Integer.class));
   // Previously solved problem on another day earns only the daily base score.
   db.update("INSERT INTO daily_checkins(id,user_id,learning_day,point_id,minutes,reflection,verification,binding_version,created_at) VALUES('old','student','2020-01-01',?,30,'重复题','VERIFIED',2,'now')",point);
   learning.settle(ticket,"old");assertEquals(1,db.queryForObject("SELECT score FROM daily_checkins WHERE id='old'",Integer.class));
   String otherPoint=(String)((Map<?,?>)learning.point(other,new LearningController.Point("PRIVATE-OTHER-HISTORY","4A","secret")).data()).get("id");learning.bind(other,new LearningController.Binding("Codeforces","other"));learning.checkin(other,new LearningController.Checkin(otherPoint,20,"private"));
   var planner=new LearningPlanner(db,json,pub,base+"/model");
   assertEquals(400,assertThrows(ResponseStatusException.class,()->planner.plan(ticket,new LearningPlanner.Plan(false))).getStatusCode().value());
   assertEquals(409,assertThrows(ResponseStatusException.class,()->planner.plan(ticket,new LearningPlanner.Plan(true))).getStatusCode().value());
   db.update("INSERT INTO user_settings(user_id,advisor_provider,advisor_api_key,advisor_endpoint) VALUES('student','fixture-model','SECRET-KEY',?)",base+"/model");
   String output=json.writeValueAsString(planner.plan(ticket,new LearningPlanner.Plan(true)).data());assertTrue(output.contains("明日复习"));assertTrue(payload.get().contains("左边界仍有疑问"));assertTrue(payload.get().contains("OJ AC 只证明"));assertFalse(payload.get().contains("PRIVATE-OTHER-HISTORY"));assertFalse(payload.get().contains("SECRET-KEY"));
   String tomorrow=LocalDate.now(LearningController.ZONE).plusDays(1).toString();
   planner.save(ticket,new LearningPlanner.SavedPlan(tomorrow,"复习不变量"));planner.save(ticket,new LearningPlanner.SavedPlan(tomorrow,"复习边界"));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_plans WHERE user_id='student'",Integer.class));
   assertEquals("复习边界",db.queryForObject("SELECT body FROM learning_plans WHERE user_id='student'",String.class));
   assertEquals(0,((List<?>)((Map<?,?>)learning.overview(other).data()).get("plans")).size());
   assertEquals(409,assertThrows(ResponseStatusException.class,()->planner.save(ticket,new LearningPlanner.SavedPlan("2020-01-01","old"))).getStatusCode().value());
  } finally {server.stop(0);database.shutdown();}
 }
 private Map<String,Object> submission(int id,long timestamp,String handle,String verdict){return Map.of("id",id,"creationTimeSeconds",timestamp,"verdict",verdict,"problem",Map.of("contestId",4,"index","A"),"author",Map.of("members",List.of(Map.of("handle",handle))));}
}
