package dev.algotogether.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class StudyWorkflowTest {
 @Test void replacedAndResubmittedTasksRejectHistoricalChecksAndUnshareNewFailures()throws Exception{
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
  try{
   var db=new JdbcTemplate(source);db.update("INSERT INTO members VALUES('leader','组长','pc',0,FALSE),('student','成员','pc',0,FALSE)");
   var tokens=new TokenCodec(GroupIntegrationTest.pub,GroupIntegrationTest.priv);String lead="Bearer "+tokens.issue("leader"),u="Bearer "+tokens.issue("student");
   var c=new StudyWorkflowController(db,new ObjectMapper(),new DataSourceTransactionManager(source),GroupIntegrationTest.pub,"A","leader");
   String plan=(String)map(c.publish(lead,new StudyWorkflowController.Publish("阶段","基础",LocalDate.now().plusDays(2).toString(),"首版",List.of(new StudyWorkflowController.Template("one","第一题","4A","解释边界")),0))).get("plan_id");
   c.enroll(u,plan,new StudyWorkflowController.Enrollment(1,30));String task=(String)map(c.claim(u,plan,new StudyWorkflowController.Claim(1,"one"))).get("id");
   String first=(String)map(c.submit(u,task,new StudyWorkflowController.Submission("第一题总结",30,"",0))).get("id");
   String pending=(String)map(c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(first,"第一题回答",""))).get("id");
   c.adjust(u,task,new StudyWorkflowController.Adjustment("REPLACE","改做变式题","5A"));
   assertEquals("OUTDATED",db.queryForObject("SELECT status FROM study_checkpoint_attempts WHERE id=?",String.class,pending));
   assertStatus(409,()->c.assess(lead,pending,new StudyWorkflowController.Assessment(true,"旧题通过")));
   assertStatus(409,()->c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(first,"旧题回答","")));
   String second=(String)map(c.submit(u,task,new StudyWorkflowController.Submission("变式总结",30,"",2))).get("id");
   String failed=(String)map(c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(second,"边界仍未理解",""))).get("id");c.assess(lead,failed,new StudyWorkflowController.Assessment(false,"需要补充边界"));
   String review=db.queryForObject("SELECT id FROM study_reviews",String.class);c.sharing(u,review,new StudyWorkflowController.Sharing(true));
   String superseded=(String)map(c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(second,"再试一次","变式"))).get("id");
   long revision=((Number)task(c,u,task).get("REVISION")).longValue();
   String latest=(String)map(c.submit(u,task,new StudyWorkflowController.Submission("修订后的总结",35,"",revision))).get("id");
   assertEquals("OUTDATED",db.queryForObject("SELECT status FROM study_checkpoint_attempts WHERE id=?",String.class,superseded));
   assertStatus(409,()->c.appeal(u,failed,new StudyWorkflowController.Reason("申诉旧提交")));
   assertStatus(409,()->c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(second,"旧提交回答","")));
   String current=(String)map(c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(latest,"新回答",""))).get("id");c.assess(lead,current,new StudyWorkflowController.Assessment(false,"新的私人反馈"));
   assertFalse(db.queryForObject("SELECT shared FROM study_reviews WHERE id=?",Boolean.class,review));
   assertEquals("NEEDS_WORK",task(c,u,task).get("STATUS"));
   c.appeal(u,current,new StudyWorkflowController.Reason("请重新检查当前回答"));
   c.adjust(u,task,new StudyWorkflowController.Adjustment("SKIP_REQUEST","下阶段再做",""));c.skip(lead,task,new StudyWorkflowController.Decision(true,"同意"));
   assertStatus(409,()->c.assess(lead,current,new StudyWorkflowController.Assessment(true,"不能再完成跳过任务")));
   assertEquals("SKIPPED",task(c,u,task).get("STATUS"));
  }finally{source.shutdown();}
 }
 @Test void adjustmentsEvidenceHelpReviewAndMemberPermissionsKeepTheirHistory()throws Exception{
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var available=new java.util.concurrent.atomic.AtomicBoolean(true);
  server.createContext("/user.status",e->{byte[] response=("{\"status\":\"OK\",\"result\":[{\"id\":456,\"creationTimeSeconds\":"+java.time.Instant.now().getEpochSecond()+",\"verdict\":\"OK\",\"problem\":{\"contestId\":4,\"index\":\"A\"},\"author\":{\"members\":[{\"handle\":\"student\"}]}}]}").getBytes();e.sendResponseHeaders(available.get()?200:503,response.length);e.getResponseBody().write(response);e.close();});server.start();
  try{
   var db=new JdbcTemplate(source);db.update("INSERT INTO members VALUES('leader','组长','pc',0,FALSE),('student','成员','pc',0,FALSE),('other','另一成员','pc',0,FALSE)");
   var tokens=new TokenCodec(GroupIntegrationTest.pub,GroupIntegrationTest.priv);String lead="Bearer "+tokens.issue("leader"),u="Bearer "+tokens.issue("student"),other="Bearer "+tokens.issue("other");
   var c=new StudyWorkflowController(db,new ObjectMapper(),new DataSourceTransactionManager(source),GroupIntegrationTest.pub,"A","leader");org.springframework.test.util.ReflectionTestUtils.setField(c,"cfBase","http://127.0.0.1:"+server.getAddress().getPort());
   var template=new StudyWorkflowController.Template("one","状态转移","4A","解释边界");String original=LocalDate.now(LearningController.ZONE).minusDays(1).toString(),later=LocalDate.now(LearningController.ZONE).plusDays(3).toString();
   String plan=(String)map(c.publish(lead,new StudyWorkflowController.Publish("阶段","动态规划",original,"首版",List.of(template,new StudyWorkflowController.Template("two","第二题","5A","说明复杂度")),0))).get("plan_id");
   c.enroll(u,plan,new StudyWorkflowController.Enrollment(1,60));String task=(String)map(c.claim(u,plan,new StudyWorkflowController.Claim(1,"one"))).get("id");assertEquals(60,((Number)task(c,u,task).get("MINUTES")).intValue());
   c.adjust(u,task,new StudyWorkflowController.Adjustment("DEFER","需要补基础",later));assertEquals(original,task(c,u,task).get("ORIGINAL_DEADLINE"));assertEquals("DEFERRED",task(c,u,task).get("STATUS"));
   c.draft(u,task,new StudyWorkflowController.Draft("私人学习过程",20,1,"尚待验证的提交说明"));assertEquals("尚待验证的提交说明",task(c,u,task).get("DRAFT_EVIDENCE"));
   String submission=(String)map(c.submit(u,task,new StudyWorkflowController.Submission("私人正式总结",20,"用户填写的说明并非 OJ 证据",2))).get("id");
   db.update("INSERT INTO oj_bindings VALUES('student','Codeforces','student',1)");c.verifyOj(u,submission);assertEquals("VERIFIED",db.queryForObject("SELECT oj_status FROM study_submissions WHERE id=?",String.class,submission));assertEquals("SUBMITTED",task(c,u,task).get("STATUS"));
   available.set(false);assertStatus(502,()->c.verifyOj(u,submission));assertEquals("VERIFIED",db.queryForObject("SELECT oj_status FROM study_submissions WHERE id=?",String.class,submission));
   assertStatus(404,()->c.verifyOj(other,submission));
   String attempt=(String)map(c.checkpoint(u,task,new StudyWorkflowController.Checkpoint(submission,"第一次错误回答",""))).get("id");c.assess(lead,attempt,new StudyWorkflowController.Assessment(false,"重新说明边界"));
   String review=db.queryForObject("SELECT id FROM study_reviews WHERE user_id='student'",String.class);assertTrue(((List<?>)c.sharedReviews(other).data()).isEmpty());
   c.sharing(u,review,new StudyWorkflowController.Sharing(true));assertEquals(1,((List<?>)c.sharedReviews(other).data()).size());c.sharing(u,review,new StudyWorkflowController.Sharing(false));assertTrue(((List<?>)c.sharedReviews(other).data()).isEmpty());
   c.review(u,review,new StudyWorkflowController.Review("复习后的理解",later));assertEquals(1,((List<?>)c.reviewHistory(u,review).data()).size());assertTrue(((List<?>)c.reviewHistory(other,review).data()).isEmpty());
   String help=(String)map(c.help(u,task,new StudyWorkflowController.Help("关于边界的求助","我主动公开的提问"))).get("id");assertFalse(db.queryForObject("SELECT body FROM discussions",String.class).contains("私人正式总结"));assertFalse(db.queryForObject("SELECT payload FROM event_log WHERE kind='discussion_created'",String.class).contains("私人正式总结"));assertTrue(db.queryForObject("SELECT payload FROM event_log WHERE kind='discussion_created'",String.class).contains("我主动公开的提问"));c.resolve(u,help,new StudyWorkflowController.Resolution("已理解空集初始值"));assertStatus(404,()->c.resolve(other,help,new StudyWorkflowController.Resolution("越权")));
   c.adjust(u,task,new StudyWorkflowController.Adjustment("REPLACE","换一道变式题","5A"));assertEquals("4A",db.queryForObject("SELECT problem_key FROM study_submissions WHERE id=?",String.class,submission));
   String second=(String)map(c.claim(u,plan,new StudyWorkflowController.Claim(1,"two"))).get("id");c.adjust(u,second,new StudyWorkflowController.Adjustment("SKIP_REQUEST","当前阶段不适合",""));assertStatus(403,()->c.skip(other,second,new StudyWorkflowController.Decision(true,"冒充组长")));c.skip(lead,second,new StudyWorkflowController.Decision(false,"先尝试简化题"));assertEquals("IN_PROGRESS",task(c,u,second).get("STATUS"));
   c.adjust(u,second,new StudyWorkflowController.Adjustment("SKIP_REQUEST","已确认调整方案",""));c.skip(lead,second,new StudyWorkflowController.Decision(true,"下阶段再安排"));assertEquals("SKIPPED",task(c,u,second).get("STATUS"));
   assertStatus(403,()->c.resource(other,new StudyWorkflowController.Resource("冒充公告","NOTICE","内容")));c.resource(lead,new StudyWorkflowController.Resource("组内公告","NOTICE","本周讨论边界"));
   c.retrospective(u,new StudyWorkflowController.Retrospective("本周卡点为边界","下一阶段先复习再做变式",true));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM study_retrospectives WHERE confirmed=TRUE",Integer.class));
   c.memberAccess(lead,"student",new StudyWorkflowController.Access(true,"临时撤销"));assertStatus(403,()->c.overview(u));c.memberAccess(lead,"student",new StudyWorkflowController.Access(false,"恢复学习"));assertEquals("5A",task(c,u,task).get("PROBLEM_KEY"));
   assertStatus(409,()->c.memberAccess(lead,"leader",new StudyWorkflowController.Access(true,"误操作")));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM study_member_access_history",Integer.class));
   db.update("UPDATE study_tasks SET status='COMPLETED',completed_at=? WHERE id=?",java.time.Instant.now().toString(),task);
   assertEquals(1L,map(c.weekly(u)).get("late"));assertEquals(0L,map(c.weekly(u)).get("on_time"));
   db.update("UPDATE study_tasks SET original_deadline=? WHERE id=?",later,task);assertEquals(1L,map(c.weekly(u)).get("on_time"));assertEquals(0L,map(c.weekly(u)).get("late"));
   db.update("UPDATE study_tasks SET status='IN_PROGRESS',completed_at=NULL,deadline=? WHERE id=?",LocalDate.now(LearningController.ZONE).toString(),task);assertEquals(1L,map(c.weekly(u)).get("unfinished"));
  }finally{server.stop(0);source.shutdown();}
 }
 @Test void versionsDraftsCheckpointsAndPrivateReviewsRemainScoped()throws Exception{
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
  try{
   var db=new JdbcTemplate(source);db.update("INSERT INTO members VALUES('leader','组长','pc',0,FALSE),('student','成员','pc',0,FALSE),('other','其他成员','pc',0,FALSE)");
   var tokens=new TokenCodec(GroupIntegrationTest.pub,GroupIntegrationTest.priv);String leader="Bearer "+tokens.issue("leader"),student="Bearer "+tokens.issue("student"),other="Bearer "+tokens.issue("other");
   var json=new ObjectMapper();var tx=new DataSourceTransactionManager(source);
   var a=new StudyWorkflowController(db,json,tx,GroupIntegrationTest.pub,"A","leader");
   var b=new StudyWorkflowController(db,json,tx,GroupIntegrationTest.pub,"B","leader");
   String deadline=LocalDate.now().plusDays(2).toString();var template=new StudyWorkflowController.Template("binary","二分边界","4A","解释循环不变量");
   var publish=new StudyWorkflowController.Publish("第一阶段","二分查找",deadline,"首次发布",List.of(template),0);
   assertStatus(403,()->a.publish(student,publish));
   String plan=(String)map(a.publish(leader,publish)).get("plan_id");
   a.enroll(student,plan,new StudyWorkflowController.Enrollment(1,45));
   String task=(String)map(a.claim(student,plan,new StudyWorkflowController.Claim(1,"binary"))).get("id");
   assertEquals(task,map(a.claim(student,plan,new StudyWorkflowController.Claim(1,"binary"))).get("id"));
   assertStatus(404,()->b.task(student,task));assertStatus(404,()->a.task(other,task));
   a.publishVersion(leader,plan,new StudyWorkflowController.Publish("新阶段","动态规划",deadline,"变更任务，不覆盖成员快照",List.of(new StudyWorkflowController.Template("binary","新标题","5A","新标准")),1));
   assertEquals("二分边界",task(a,student,task).get("TITLE"));assertEquals(1,((Number)task(a,student,task).get("PLAN_VERSION")).intValue());
   a.draft(student,task,new StudyWorkflowController.Draft("过程草稿",30,0));
   assertStatus(409,()->a.draft(student,task,new StudyWorkflowController.Draft("旧窗口覆盖",20,0)));
   assertEquals("过程草稿",task(a,student,task).get("DRAFT"));
   String submission=(String)map(a.submit(student,task,new StudyWorkflowController.Submission("总结",30,"OJ 待验证",1))).get("id");
   assertStatus(409,()->a.submit(student,task,new StudyWorkflowController.Submission("重复提交",30,"",2)));
   String attempt=(String)map(a.checkpoint(student,task,new StudyWorkflowController.Checkpoint(submission,"第一次回答",""))).get("id");
   a.assess(leader,attempt,new StudyWorkflowController.Assessment(false,"边界解释不足"));
   assertEquals("NEEDS_WORK",task(a,student,task).get("STATUS"));
   assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM study_reviews WHERE group_id='A' AND shared=FALSE",Integer.class));
   assertTrue(((List<?>)a.sharedReviews(other).data()).isEmpty());
   assertFalse(json.writeValueAsString(a.overview(other).data()).contains("边界解释不足"));
   assertFalse(json.writeValueAsString(b.overview(student).data()).contains("二分边界"));
   a.appeal(student,attempt,new StudyWorkflowController.Reason("请复核我的边界示例"));
   a.assess(leader,attempt,new StudyWorkflowController.Assessment(true,"复核通过"));
   assertEquals("COMPLETED",task(a,student,task).get("STATUS"));
   assertEquals("NOT_CHECKED",db.queryForObject("SELECT oj_status FROM study_submissions WHERE id=?",String.class,submission));
   assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM study_events WHERE kind='CHECKPOINT_REVIEW'",Integer.class));
   a.enroll(other,plan,new StudyWorkflowController.Enrollment(1,30));String concurrent=(String)map(a.claim(other,plan,new StudyWorkflowController.Claim(1,"binary"))).get("id");
   var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
   try{
    java.util.concurrent.Callable<Integer> write=()->{start.await();try{a.draft(other,concurrent,new StudyWorkflowController.Draft(Thread.currentThread().getName(),30,0));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}};
    var one=executor.submit(write);var two=executor.submit(write);start.countDown();
    assertEquals(Set.of(200,409),Set.of(one.get(5,java.util.concurrent.TimeUnit.SECONDS),two.get(5,java.util.concurrent.TimeUnit.SECONDS)));
    assertEquals(1L,((Number)task(a,other,concurrent).get("REVISION")).longValue());
   }finally{executor.shutdownNow();}
   db.update("UPDATE members SET revoked=TRUE WHERE user_id='student'");assertStatus(403,()->a.overview(student));
  }finally{source.shutdown();}
 }
 private static Map<?,?> map(Api<?> api){return (Map<?,?>)api.data();}
 private static Map<?,?> task(StudyWorkflowController c,String h,String id){return (Map<?,?>)map(c.task(h,id)).get("task");}
 private static void assertStatus(int status,org.junit.jupiter.api.function.Executable call){assertEquals(status,assertThrows(ResponseStatusException.class,call).getStatusCode().value());}
}
