package dev.algotogether.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.algotogether.contracts.TokenCodec;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class GroupLearningTest {
 @Test void parallelTasksStayInTheirGroupAndPrivateMemberRecords()throws Exception{
  var first=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();var second=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/user.status",e->{byte[] b=("{\"status\":\"OK\",\"result\":[{\"id\":123,\"creationTimeSeconds\":"+Instant.now().getEpochSecond()+",\"verdict\":\"OK\",\"problem\":{\"contestId\":4,\"index\":\"A\"},\"author\":{\"members\":[{\"handle\":\"student\"}]}}]}").getBytes();e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
  try{
   var json=new ObjectMapper();var db=new JdbcTemplate(first);var db2=new JdbcTemplate(second);for(var store:List.of(db,db2))store.update("INSERT INTO members VALUES('student','同伴','pc',0,FALSE),('other','另一成员','pc',0,FALSE)");
   var tokens=new TokenCodec(GroupIntegrationTest.pub,GroupIntegrationTest.priv);String auth="Bearer "+tokens.issue("student"),other="Bearer "+tokens.issue("other");String base="http://127.0.0.1:"+server.getAddress().getPort();
   var a=new LearningController(db,json,new DataSourceTransactionManager(first),GroupIntegrationTest.pub,base);var b=new LearningController(db2,json,new DataSourceTransactionManager(second),GroupIntegrationTest.pub,base);
   assertEquals(403,assertThrows(ResponseStatusException.class,()->a.overview("Bearer "+tokens.issue("outsider"))).getStatusCode().value());
   a.bind(auth,new LearningController.Binding("Codeforces","student"));b.bind(auth,new LearningController.Binding("Codeforces","student"));
   String p1=id(a.point(auth,new LearningController.Point("A-目标一","4A","检查一"))),p2=id(a.point(auth,new LearningController.Point("A-目标二","4A","检查二"))),pb=id(b.point(auth,new LearningController.Point("B-目标","4A","检查 B")));
   String c1=id(a.checkin(auth,new LearningController.Checkin(p1,20,"A-复盘一"))),c2=id(a.checkin(auth,new LearningController.Checkin(p2,30,"A-复盘二")));b.checkin(auth,new LearningController.Checkin(pb,15,"B-复盘"));
   assertEquals(409,assertThrows(ResponseStatusException.class,()->a.checkin(auth,new LearningController.Checkin(p1,20,"重复"))).getStatusCode().value());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->b.checkin(auth,new LearningController.Checkin(p1,20,"跨组"))).getStatusCode().value());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->a.verify(other,c1)).getStatusCode().value());
   assertEquals(409,assertThrows(ResponseStatusException.class,()->a.settle(auth,c1)).getStatusCode().value());
   a.verify(auth,c1);a.verify(auth,c2);a.settle(auth,c1);a.settle(auth,c1);a.settle(auth,c2);
   assertEquals(3,db.queryForObject("SELECT score FROM daily_checkins WHERE id=?",Integer.class,c1));assertEquals(1,db.queryForObject("SELECT score FROM daily_checkins WHERE id=?",Integer.class,c2));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM learning_awards",Integer.class));
   assertFalse(json.writeValueAsString(a.overview(auth).data()).contains("B-复盘"));assertFalse(json.writeValueAsString(a.overview(other).data()).contains("A-复盘"));
   String tomorrow=LocalDate.now(LearningController.ZONE).plusDays(1).toString();a.savePlan(auth,new LearningController.SavedPlan(tomorrow,"仅 A 明日计划"));assertFalse(json.writeValueAsString(b.overview(auth).data()).contains("仅 A"));
   db.update("UPDATE members SET revoked=TRUE WHERE user_id='student'");assertEquals(403,assertThrows(ResponseStatusException.class,()->a.overview(auth)).getStatusCode().value());
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM event_log",Integer.class));
  }finally{server.stop(0);first.shutdown();second.shutdown();}
 }
 private String id(dev.algotogether.contracts.Api<?> response){return (String)((Map<?,?>)response.data()).get("id");}
}
