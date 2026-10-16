package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.algotogether.contracts.TokenCodec;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class GroupLearningPlannerTest {
 @Test void plannerUsesOnlyRequestedGroupAndRechecksMembership()throws Exception{
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();var db=new JdbcTemplate(source);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var payload=new AtomicReference<String>();var allowed=new AtomicBoolean(true);var revokeInModel=new AtomicBoolean(false);
  String auth="Bearer "+new TokenCodec(IdentityIntegrationTest.pub,IdentityIntegrationTest.priv).issue("student");
  server.createContext("/api/v1/group/learning/overview",e->{assertEquals(auth,e.getRequestHeaders().getFirst("Authorization"));byte[] body="{\"data\":{\"checkins\":[{\"TITLE\":\"GROUP-A-TASK\",\"REFLECTION\":\"GROUP-A-HISTORY\"}]}}".getBytes();e.sendResponseHeaders(allowed.get()?200:403,body.length);e.getResponseBody().write(body);e.close();});
  server.createContext("/api/v1/group/study/overview",e->{byte[] body="{\"data\":{\"recent_submissions\":[{\"TITLE\":\"GROUP-A-V2-TASK\",\"BODY\":\"GROUP-A-V2-HISTORY\",\"OJ_STATUS\":\"NOT_CHECKED\",\"TASK_STATUS\":\"SUBMITTED\"}]}}".getBytes();e.sendResponseHeaders(allowed.get()?200:403,body.length);e.getResponseBody().write(body);e.close();});
  server.createContext("/model",e->{payload.set(new String(e.getRequestBody().readAllBytes()));if(revokeInModel.get())allowed.set(false);byte[] body="{\"choices\":[{\"message\":{\"content\":\"GROUP-A-PLAN\"}}]}".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  try{
   String base="http://127.0.0.1:"+server.getAddress().getPort();db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES('student','student@fixture.test','同伴','unused')");db.update("INSERT INTO user_settings(user_id,advisor_provider,advisor_api_key,advisor_endpoint) VALUES('student','fixture','secret',?)",base+"/model");db.update("INSERT INTO learning_points VALUES('legacy','student','LEGACY-PERSONAL-TASK','4A','legacy','now')");db.update("INSERT INTO daily_checkins(id,user_id,learning_day,point_id,minutes,reflection,verification,created_at) VALUES('legacy','student','2020-01-01','legacy',20,'LEGACY-PERSONAL-HISTORY','PENDING','now')");
   var planner=new LearningPlanner(db,new ObjectMapper(),IdentityIntegrationTest.pub,base+"/model");ReflectionTestUtils.setField(planner,"groupServices",new GroupServiceLocator(db,base,base));
   assertEquals("GROUP-A-PLAN",((Map<?,?>)planner.plan(auth,new LearningPlanner.Plan(true,"group-default")).data()).get("answer"));assertTrue(payload.get().contains("GROUP-A-HISTORY"));assertFalse(payload.get().contains("LEGACY-PERSONAL-HISTORY"));
   assertTrue(payload.get().contains("GROUP-A-V2-HISTORY"));assertTrue(payload.get().contains("SUBMITTED"));
   allowed.set(false);assertEquals(403,assertThrows(ResponseStatusException.class,()->planner.plan(auth,new LearningPlanner.Plan(true,"group-default"))).getStatusCode().value());
   allowed.set(true);revokeInModel.set(true);assertEquals(403,assertThrows(ResponseStatusException.class,()->planner.plan(auth,new LearningPlanner.Plan(true,"group-default"))).getStatusCode().value());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->planner.plan(auth,new LearningPlanner.Plan(true,"unconfigured-group"))).getStatusCode().value());
  }finally{server.stop(0);source.shutdown();}
 }
}
