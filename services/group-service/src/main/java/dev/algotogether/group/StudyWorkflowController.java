package dev.algotogether.group;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.net.URI;
import java.net.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Every query is scoped by server-owned group ID and a verified member, never a client group parameter. */
@RestController @RequestMapping("/api/v1/group/study")
public class StudyWorkflowController {
 private final JdbcTemplate db;
 private final TokenCodec tokens;
 private final ObjectMapper json;
 private final TransactionTemplate tx;
 private final String group,owner;
 @Value("${learning.codeforces-url:https://codeforces.com/api}") private String cfBase="https://codeforces.com/api";
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
 public StudyWorkflowController(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,
   @Value("${identity.public-key}") String key,@Value("${group.id}") String group,@Value("${group.owner-id}") String owner){
  this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);this.tokens=new TokenCodec(key,null);this.group=group;this.owner=owner;
 }
 private String member(String h){String u=tokens.verify(h);if(count("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",u)==0)throw Api.error(HttpStatus.FORBIDDEN,"请先加入当前群组");return u;}
 private String leader(String h){String u=member(h);if(!u.equals(owner))throw Api.error(HttpStatus.FORBIDDEN,"仅本群组组长可执行此操作");return u;}
 private void lockMember(String u){if(db.queryForList("SELECT user_id FROM members WHERE user_id=? AND revoked=FALSE FOR UPDATE",u).isEmpty())throw Api.error(HttpStatus.FORBIDDEN,"当前群组成员权限已失效");}
 private int count(String sql,Object...args){return db.queryForObject(sql,Integer.class,args);}
 private static String id(){return UUID.randomUUID().toString();}
 private static String now(){return Instant.now().toString();}
 private static String today(){return LocalDate.now(LearningController.ZONE).toString();}
 private static String day(String value){try{return LocalDate.parse(value).toString();}catch(Exception e){throw Api.error(HttpStatus.BAD_REQUEST,"日期必须为 YYYY-MM-DD");}}
 private static String text(Map<String,Object> m,String key){return Objects.toString(m.get(key),"");}
 private static void require(boolean b,String message){if(!b)throw Api.error(HttpStatus.CONFLICT,message);}
 private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> ownTask(String task,String user,boolean lock){var rows=db.queryForList("SELECT * FROM study_tasks WHERE group_id=? AND id=? AND user_id=?"+(lock?" FOR UPDATE":""),group,task,user);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"本群组中不存在你的这项任务");return rows.getFirst();}
 private Map<String,Object> plan(String plan,int version){var rows=db.queryForList("SELECT * FROM study_plan_versions WHERE group_id=? AND plan_id=? AND version=?",group,plan,version);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"当前群组没有此计划版本");return rows.getFirst();}
 private void event(String task,String user,String kind,String reason,String detail){db.update("INSERT INTO study_events(group_id,id,task_id,user_id,kind,reason,detail,created_at) VALUES(?,?,?,?,?,?,?,?)",group,id(),task,user,kind,reason,detail,now());}
 private void invalidatePending(String task,String user){db.update("UPDATE study_checkpoint_attempts SET status='OUTDATED',feedback='任务题目或正式提交已调整；此检查保留为历史，请对新提交重新检查' WHERE group_id=? AND task_id=? AND user_id=? AND status IN ('PENDING','APPEALED')",group,task,user);}
 private void currentSubmission(Map<String,Object> task,String submission,String user){
  var rows=db.queryForList("SELECT id,problem_key FROM study_submissions WHERE group_id=? AND task_id=? AND user_id=? ORDER BY created_at DESC LIMIT 1",group,task.get("ID"),user);
  require(!rows.isEmpty()&&submission.equals(rows.getFirst().get("ID"))&&Objects.equals(task.get("PROBLEM_KEY"),rows.getFirst().get("PROBLEM_KEY")),"检查必须关联当前题目的最新正式提交；历史检查与申诉记录继续保留");
 }
 private List<Template> templates(Map<String,Object> plan){try{return json.readValue(text(plan,"TASKS_JSON"),new TypeReference<List<Template>>(){});}catch(Exception e){throw new IllegalStateException(e);}}
 public record Template(@NotBlank @Size(max=36)String id,@NotBlank @Size(max=120)String title,
  @NotBlank @Size(max=80)String problem_key,@NotBlank @Size(max=2000)String criterion){}
 public record Publish(@NotBlank @Size(max=120)String title,@NotBlank @Size(max=120)String stage,
  @NotBlank String deadline,@NotBlank @Size(max=2000)String change_note,@Size(min=1,max=50) @NotNull List<@Valid Template> tasks,@Min(0)int expected_version){}
 public record Enrollment(@Min(1)int version,@Min(1)@Max(1440)int minutes){}
 public record Claim(@Min(1)int version,@NotBlank String template_id){}
 public record Draft(@NotNull @Size(max=12000)String body,@Min(0)@Max(1440)int minutes,@Min(0)long revision,@Size(max=2000)String evidence_note){public Draft(String body,int minutes,long revision){this(body,minutes,revision,"");}}
 public record Submission(@NotBlank @Size(max=12000)String body,@Min(1)@Max(1440)int minutes,@NotNull @Size(max=2000)String evidence_note,@Min(0)long revision){}
 public record Adjustment(@NotBlank @Pattern(regexp="DEFER|REPLACE|SKIP_REQUEST|RESUME")String kind,@NotBlank @Size(max=2000)String reason,@Size(max=80)String value){}
 public record Decision(boolean approved,@NotBlank @Size(max=2000)String reason){}
 public record Checkpoint(@NotBlank String submission_id,@NotBlank @Size(max=12000)String answer,@NotNull @Size(max=2000)String variant){}
 public record Assessment(boolean passed,@NotBlank @Size(max=4000)String feedback){}
 public record Reason(@NotBlank @Size(max=2000)String reason){}
 public record Review(@NotBlank @Size(max=4000)String reflection,@NotBlank String next_day){}
 public record Sharing(boolean shared){}
 public record Help(@NotBlank @Size(max=100)String title,@NotBlank @Size(max=12000)String body){}
 public record Resolution(@NotBlank @Size(max=4000)String understanding){}
 public record Retrospective(@NotBlank @Size(max=4000)String body,@NotBlank @Size(max=4000)String next_plan,boolean confirmed){}
 public record Resource(@NotBlank @Size(max=120)String title,@NotBlank @Pattern(regexp="ARTICLE|KNOWLEDGE|PROBLEMS|NOTICE")String kind,@NotBlank @Size(max=20000)String body){}
 public record Access(boolean revoked,@NotBlank @Size(max=2000)String reason){}
 @GetMapping("/member-access") Api<?> memberAccess(@RequestHeader(value="Authorization",required=false)String h){String u=member(h);return Api.of(Map.of("is_leader",u.equals(owner),"members",u.equals(owner)?db.queryForList("SELECT user_id,nickname,revoked FROM members ORDER BY nickname"):List.of()));}
 @PutMapping("/member-access/{id}") Api<?> memberAccess(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Access a){String u=leader(h);require(!id.equals(owner),"不能撤销组长自己的成员资格");return tx.execute(t->{lockMember(u);if(db.update("UPDATE members SET revoked=? WHERE user_id=?",a.revoked(),id)==0)throw Api.error(HttpStatus.NOT_FOUND,"本群组成员不存在");db.update("INSERT INTO study_member_access_history VALUES(?,?,?,?,?,?,?)",group,id(),id,u,a.revoked(),a.reason(),now());return Api.of(Map.of("revoked",a.revoked()));});}

 @GetMapping("/overview") Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){String u=member(h);var out=new LinkedHashMap<String,Object>();
  out.put("group_id",group);out.put("today",today());out.put("is_leader",u.equals(owner));
  out.put("binding",db.queryForList("SELECT provider,handle,version FROM oj_bindings WHERE user_id=?",u));
  out.put("announcements",db.queryForList("SELECT title,body,id,created_at FROM study_resources WHERE group_id=? AND kind='NOTICE' ORDER BY created_at DESC LIMIT 5",group));
  out.put("recent_submissions",db.queryForList("SELECT s.*,t.title,t.criterion,t.status AS task_status FROM study_submissions s JOIN study_tasks t ON s.group_id=t.group_id AND s.task_id=t.id WHERE s.group_id=? AND s.user_id=? ORDER BY s.created_at DESC LIMIT 30",group,u));
  out.put("plans",db.queryForList("SELECT p.* FROM study_plan_versions p WHERE p.group_id=? AND p.version=(SELECT MAX(v.version) FROM study_plan_versions v WHERE v.group_id=p.group_id AND v.plan_id=p.plan_id) ORDER BY p.created_at DESC",group));
  out.put("enrollments",db.queryForList("SELECT * FROM study_enrollments WHERE group_id=? AND user_id=?",group,u));
  out.put("tasks",db.queryForList("SELECT * FROM study_tasks WHERE group_id=? AND user_id=? ORDER BY deadline,created_at",group,u));
  out.put("reviews",db.queryForList("SELECT * FROM study_reviews WHERE group_id=? AND user_id=? ORDER BY due_day",group,u));
  out.put("attempts",db.queryForList("SELECT * FROM study_checkpoint_attempts WHERE group_id=? AND user_id=? ORDER BY created_at DESC",group,u));
  out.put("retrospectives",db.queryForList("SELECT * FROM study_retrospectives WHERE group_id=? AND user_id=? ORDER BY created_at DESC LIMIT 30",group,u));
  out.put("adjustments",db.queryForList("SELECT * FROM study_events WHERE group_id=? AND user_id=? ORDER BY created_at DESC LIMIT 100",group,u));
  return Api.of(out);
 }
 @PostMapping("/plans") Api<?> publish(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Publish p){return publishVersion(h,id(),p);}
 @PostMapping("/plans/{plan}/versions") Api<?> publishVersion(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String plan,@Valid @RequestBody Publish p){String u=leader(h);day(p.deadline());
  require(p.tasks().stream().map(Template::id).distinct().count()==p.tasks().size(),"计划内任务标识不能重复");
  return tx.execute(t->{lockMember(u);int latest=db.queryForObject("SELECT COALESCE(MAX(version),0) FROM study_plan_versions WHERE group_id=? AND plan_id=?",Integer.class,group,plan);
   require(latest==p.expected_version(),"计划已更新，请刷新后发布新版本");int version=latest+1;
   db.update("INSERT INTO study_plan_versions(group_id,plan_id,version,author_id,title,stage,deadline,change_note,tasks_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",group,plan,version,u,p.title(),p.stage(),p.deadline(),p.change_note(),encode(p.tasks()),now());
   return Api.of(Map.of("plan_id",plan,"version",version,"group_id",group));});
 }
 @GetMapping("/plans/{plan}/versions") Api<?> versions(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String plan){member(h);return Api.of(db.queryForList("SELECT * FROM study_plan_versions WHERE group_id=? AND plan_id=? ORDER BY version DESC",group,plan));}
 @GetMapping("/plans/{plan}/export") Api<?> export(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String plan){member(h);Integer version=db.queryForObject("SELECT MAX(version) FROM study_plan_versions WHERE group_id=? AND plan_id=?",Integer.class,group,plan);if(version==null)throw Api.error(HttpStatus.NOT_FOUND,"计划不存在");var p=plan(plan,version);return Api.of(Map.of("title",p.get("TITLE"),"stage",p.get("STAGE"),"deadline",p.get("DEADLINE"),"change_note","从群组 "+group+" 的计划 v"+version+" 复制；复制后独立管理","tasks",templates(p),"expected_version",0));}
 @PostMapping("/plans/{plan}/join") Api<?> enroll(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String plan,@Valid @RequestBody Enrollment e){String u=member(h);return tx.execute(t->{lockMember(u);plan(plan,e.version());
  if(db.update("UPDATE study_enrollments SET version=?,minutes=?,confirmed_at=? WHERE group_id=? AND user_id=? AND plan_id=?",e.version(),e.minutes(),now(),group,u,plan)==0)db.update("INSERT INTO study_enrollments VALUES(?,?,?,?,?,?)",group,u,plan,e.version(),e.minutes(),now());
  return Api.of(Map.of("joined",true,"version",e.version(),"existing_tasks_unchanged",true));});}
 @PostMapping("/plans/{plan}/claim") Api<?> claim(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String plan,@Valid @RequestBody Claim c){String u=member(h);return tx.execute(t->{lockMember(u);var p=plan(plan,c.version());
  require(count("SELECT COUNT(*) FROM study_enrollments WHERE group_id=? AND user_id=? AND plan_id=? AND version=?",group,u,plan,c.version())==1,"请先确认加入这个版本的计划");
  var template=templates(p).stream().filter(x->x.id().equals(c.template_id())).findFirst().orElseThrow(()->Api.error(HttpStatus.NOT_FOUND,"计划任务不存在"));
  var existing=db.queryForList("SELECT id FROM study_tasks WHERE group_id=? AND user_id=? AND plan_id=? AND template_id=?",group,u,plan,c.template_id());
  if(!existing.isEmpty())return Api.of(Map.of("id",existing.getFirst().get("ID"),"existing",true));
  Integer minutes=db.queryForObject("SELECT minutes FROM study_enrollments WHERE group_id=? AND user_id=? AND plan_id=?",Integer.class,group,u,plan);
  String id=id();db.update("INSERT INTO study_tasks(group_id,id,user_id,plan_id,plan_version,template_id,title,problem_key,criterion,deadline,original_deadline,status,draft,minutes,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'NOT_STARTED','',?,?)",group,id,u,plan,c.version(),c.template_id(),template.title(),template.problem_key(),template.criterion(),p.get("DEADLINE"),p.get("DEADLINE"),minutes,now());
  event(id,u,"CLAIM","领取任务","计划版本 "+c.version());return Api.of(Map.of("id",id));});}
 @GetMapping("/tasks/{id}") Api<?> task(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String u=member(h);var out=new LinkedHashMap<String,Object>();out.put("task",ownTask(id,u,false));
  out.put("submissions",db.queryForList("SELECT * FROM study_submissions WHERE group_id=? AND task_id=? AND user_id=? ORDER BY created_at DESC",group,id,u));
  out.put("attempts",db.queryForList("SELECT * FROM study_checkpoint_attempts WHERE group_id=? AND task_id=? AND user_id=? ORDER BY created_at DESC",group,id,u));
  out.put("events",db.queryForList("SELECT * FROM study_events WHERE group_id=? AND task_id=? AND user_id=? ORDER BY created_at DESC",group,id,u));
  out.put("help",db.queryForList("SELECT * FROM study_help WHERE group_id=? AND task_id=? AND user_id=? ORDER BY created_at DESC",group,id,u));
  out.put("oj_evidence",db.queryForList("SELECT e.* FROM study_oj_evidence e JOIN study_submissions s ON e.group_id=s.group_id AND e.submission_id=s.id WHERE s.group_id=? AND s.task_id=? AND s.user_id=?",group,id,u));
  out.put("resources",db.queryForList("SELECT * FROM study_resources WHERE group_id=? ORDER BY created_at DESC LIMIT 10",group));
  return Api.of(out);}
 @PostMapping("/submissions/{id}/verify-oj") Api<?> verifyOj(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String u=member(h);var submissions=db.queryForList("SELECT * FROM study_submissions WHERE group_id=? AND id=? AND user_id=?",group,id,u);if(submissions.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"提交不存在");var submission=submissions.getFirst();
  String problem=text(submission,"PROBLEM_KEY");require(problem.matches("[0-9]{1,7}[A-Z][0-9]?"),"自动 OJ 验证目前仅支持 Codeforces 题号，如 4A");
  var bindings=db.queryForList("SELECT * FROM oj_bindings WHERE user_id=?",u);require(!bindings.isEmpty(),"请先在本群组绑定 Codeforces");var binding=bindings.getFirst();String handle=text(binding,"HANDLE"),found="";
  String submittedDay=Instant.parse(text(submission,"CREATED_AT")).atZone(LearningController.ZONE).toLocalDate().toString();
  try{
   var response=http.send(HttpRequest.newBuilder(URI.create(cfBase+"/user.status?handle="+handle+"&from=1&count=1000")).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200)throw new IllegalStateException();var data=json.readTree(response.body());if(!"OK".equals(data.path("status").asText()))throw new IllegalStateException();
   for(var s:data.path("result")){String key=s.path("problem").path("contestId").asText()+s.path("problem").path("index").asText();boolean author=false;for(var m:s.path("author").path("members"))if(handle.equalsIgnoreCase(m.path("handle").asText()))author=true;String when=Instant.ofEpochSecond(s.path("creationTimeSeconds").asLong()).atZone(LearningController.ZONE).toLocalDate().toString();if(author&&"OK".equals(s.path("verdict").asText())&&problem.equals(key)&&submittedDay.equals(when)){found=s.path("id").asText();break;}}
   if(found.isEmpty()&&data.path("result").size()>=1000&&Instant.ofEpochSecond(data.path("result").path(999).path("creationTimeSeconds").asLong()).atZone(LearningController.ZONE).toLocalDate().toString().compareTo(submittedDay)>=0)throw new IllegalStateException();
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw Api.error(HttpStatus.BAD_GATEWAY,"OJ 验证暂不可用或提交覆盖范围不足，保留原记录，可重试");}
  String evidence=found;return tx.execute(t->{lockMember(u);Long current=db.queryForObject("SELECT version FROM oj_bindings WHERE user_id=?",Long.class,u);require(current.longValue()==((Number)binding.get("VERSION")).longValue(),"OJ 绑定已变化，请重试");
   db.update("UPDATE study_submissions SET oj_status=? WHERE group_id=? AND id=? AND user_id=?",evidence.isEmpty()?"NO_MATCH":"VERIFIED",group,id,u);
   if(db.update("UPDATE study_oj_evidence SET handle=?,binding_version=?,evidence_id=?,checked_at=? WHERE group_id=? AND submission_id=?",handle,current,evidence,now(),group,id)==0)db.update("INSERT INTO study_oj_evidence VALUES(?,?,?,?,?,?)",group,id,handle,current,evidence,now());
   return Api.of(Map.of("status",evidence.isEmpty()?"NO_MATCH":"VERIFIED","evidence_id",evidence,"handle",handle,"binding_version",current,"mastery","NOT_ASSERTED"));});
 }
 @PutMapping("/tasks/{id}/draft") Api<?> draft(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Draft d){String u=member(h);return tx.execute(t->{lockMember(u);var task=ownTask(id,u,true);
  require(!Set.of("COMPLETED","SKIPPED").contains(text(task,"STATUS")),"已完成或跳过的任务不可修改草稿");
  require(((Number)task.get("REVISION")).longValue()==d.revision(),"草稿已在另一窗口更新，请刷新后重试");
  db.update("UPDATE study_tasks SET draft=?,draft_evidence=?,minutes=?,revision=revision+1,status=CASE WHEN status='NOT_STARTED' THEN 'IN_PROGRESS' ELSE status END WHERE group_id=? AND id=? AND user_id=?",d.body(),Objects.toString(d.evidence_note(),""),d.minutes(),group,id,u);
  return Api.of(ownTask(id,u,false));});}
 @PostMapping("/tasks/{id}/submit") Api<?> submit(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Submission s){String u=member(h);return tx.execute(t->{lockMember(u);var task=ownTask(id,u,true);
  require(!Set.of("SUBMITTED","COMPLETED","SKIPPED").contains(text(task,"STATUS")),"当前任务已提交、完成或跳过，请勿重复提交");
  require(((Number)task.get("REVISION")).longValue()==s.revision(),"草稿版本已变化，请刷新后提交");String submission=id();
  db.update("INSERT INTO study_submissions VALUES(?,?,?,?,?,?,?,?,?,?)",group,submission,id,u,s.body(),s.minutes(),task.get("PROBLEM_KEY"),s.evidence_note(),"NOT_CHECKED",now());
  invalidatePending(id,u);
  db.update("UPDATE study_tasks SET status='SUBMITTED',draft=?,draft_evidence=?,minutes=?,revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",s.body(),s.evidence_note(),s.minutes(),group,id,u);
  event(id,u,"SUBMIT","正式提交",submission);return Api.of(Map.of("id",submission,"summary_submitted",true,"oj_status","NOT_CHECKED","checkpoint_status","NOT_CHECKED","mastery","NOT_ASSERTED"));});}
 @PostMapping("/tasks/{id}/adjust") Api<?> adjust(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Adjustment a){String u=member(h);return tx.execute(t->{lockMember(u);var task=ownTask(id,u,true);
  require(!Set.of("COMPLETED","SKIPPED").contains(text(task,"STATUS")),"已结束的任务不可调整");
  switch(a.kind()){
   case "DEFER" -> {String deadline=day(a.value());require(deadline.compareTo(text(task,"DEADLINE"))>0,"新截止日期必须晚于当前截止日期");db.update("UPDATE study_tasks SET deadline=?,status='DEFERRED',revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",deadline,group,id,u);}
   case "REPLACE" -> {require(a.value()!=null&&!a.value().isBlank(),"请填写替换题目");invalidatePending(id,u);db.update("UPDATE study_tasks SET problem_key=?,status='IN_PROGRESS',revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",a.value(),group,id,u);}
   case "RESUME" -> db.update("UPDATE study_tasks SET status='IN_PROGRESS',revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",group,id,u);
   case "SKIP_REQUEST" -> {require(!"SKIP_PENDING".equals(task.get("STATUS")),"跳过申请正在处理中");db.update("UPDATE study_tasks SET status='SKIP_PENDING',revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",group,id,u);}
   default -> throw Api.error(HttpStatus.BAD_REQUEST,"不支持的调整操作");
  }
  event(id,u,a.kind(),a.reason(),Objects.toString(a.value(),""));return Api.of(ownTask(id,u,false));});}
 @GetMapping("/pending") Api<?> pending(@RequestHeader(value="Authorization",required=false)String h){leader(h);return Api.of(Map.of(
  "skips",db.queryForList("SELECT id,title,user_id FROM study_tasks WHERE group_id=? AND status='SKIP_PENDING'",group),
  "checkpoints",db.queryForList("SELECT a.*,t.title,t.criterion FROM study_checkpoint_attempts a JOIN study_tasks t ON a.group_id=t.group_id AND a.task_id=t.id WHERE a.group_id=? AND a.status IN ('PENDING','APPEALED') ORDER BY a.created_at",group)));}
 @PostMapping("/tasks/{id}/skip-decision") Api<?> skip(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Decision d){String u=leader(h);return tx.execute(t->{lockMember(u);var rows=db.queryForList("SELECT * FROM study_tasks WHERE group_id=? AND id=?",group,id);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"任务不存在");String student=text(rows.getFirst(),"USER_ID");lockMember(student);var task=ownTask(id,student,true);require("SKIP_PENDING".equals(task.get("STATUS")),"该任务没有待审批的跳过申请");
  db.update("UPDATE study_tasks SET status=?,revision=revision+1 WHERE group_id=? AND id=?",d.approved()?"SKIPPED":"IN_PROGRESS",group,id);if(d.approved())invalidatePending(id,text(task,"USER_ID"));event(id,text(task,"USER_ID"),"SKIP_DECISION",d.reason(),"组长 "+u+"："+d.approved());return Api.of(Map.of("approved",d.approved()));});}
 @PostMapping("/tasks/{id}/checkpoint") Api<?> checkpoint(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Checkpoint c){String u=member(h);return tx.execute(t->{lockMember(u);var task=ownTask(id,u,true);
  require(!Set.of("SKIPPED","SKIP_PENDING","COMPLETED").contains(task.get("STATUS")),"当前任务已结束或正在申请跳过");
  require(count("SELECT COUNT(*) FROM study_submissions WHERE group_id=? AND id=? AND task_id=? AND user_id=? AND problem_key=?",group,c.submission_id(),id,u,task.get("PROBLEM_KEY"))==1,"检查必须关联自己对当前题目的正式提交；换题后请重新提交");
  currentSubmission(task,c.submission_id(),u);
  require(count("SELECT COUNT(*) FROM study_checkpoint_attempts WHERE group_id=? AND task_id=? AND user_id=? AND status IN ('PENDING','APPEALED','PROCESSING')",group,id,u)==0,"已有检查或申诉正在处理中");
  String attempt=id();db.update("INSERT INTO study_checkpoint_attempts(group_id,id,task_id,user_id,submission_id,answer,variant,status,feedback,appeal,created_at) VALUES(?,?,?,?,?,?,?,'PENDING','','',?)",group,attempt,id,u,c.submission_id(),c.answer(),c.variant(),now());
  return Api.of(Map.of("id",attempt,"status","PENDING","message","等待组长检查，历次回答均保留"));});}
 @PostMapping("/checkpoints/{id}/assess") Api<?> assess(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Assessment a){String u=leader(h);return tx.execute(t->{lockMember(u);var rows=db.queryForList("SELECT * FROM study_checkpoint_attempts WHERE group_id=? AND id=?",group,id);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"检查记录不存在");
  String task=text(rows.getFirst(),"TASK_ID"),student=text(rows.getFirst(),"USER_ID");lockMember(student);var current=ownTask(task,student,true);var attempt=db.queryForList("SELECT * FROM study_checkpoint_attempts WHERE group_id=? AND id=? FOR UPDATE",group,id).getFirst();require(Set.of("PENDING","APPEALED").contains(attempt.get("STATUS")),"该检查已经处理");
  currentSubmission(current,text(attempt,"SUBMISSION_ID"),student);
  require(!Set.of("SKIPPED","SKIP_PENDING").contains(current.get("STATUS")),"请先处理跳过申请，再检查当前任务");
  db.update("UPDATE study_checkpoint_attempts SET status=?,feedback=?,reviewed_at=? WHERE group_id=? AND id=?",a.passed()?"PASSED":"NEEDS_WORK",a.feedback(),now(),group,id);
  if(!Set.of("SKIPPED","SKIP_PENDING").contains(current.get("STATUS")))db.update("UPDATE study_tasks SET status=?,completed_at=?,revision=revision+1 WHERE group_id=? AND id=? AND user_id=?",a.passed()?"COMPLETED":"NEEDS_WORK",a.passed()?now():null,group,task,student);
  if(!a.passed())queueReview(task,student,a.feedback());event(task,student,"CHECKPOINT_REVIEW",a.feedback(),"组长 "+u+"："+(a.passed()?"PASSED":"NEEDS_WORK"));
  return Api.of(Map.of("checkpoint_passed",a.passed(),"mastery","NOT_ASSERTED"));});}
 private void queueReview(String task,String user,String reason){if(db.update("UPDATE study_reviews SET status='DUE',reason=?,due_day=?,shared=FALSE WHERE group_id=? AND user_id=? AND task_id=?",reason,today(),group,user,task)==0)db.update("INSERT INTO study_reviews VALUES(?,?,?,?,?,?,'DUE',FALSE)",group,id(),task,user,reason,today());}
 @PostMapping("/checkpoints/{id}/appeal") Api<?> appeal(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Reason r){String u=member(h);return tx.execute(t->{lockMember(u);var rows=db.queryForList("SELECT * FROM study_checkpoint_attempts WHERE group_id=? AND id=? AND user_id=? FOR UPDATE",group,id,u);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"检查记录不存在");var a=rows.getFirst();require(Set.of("PASSED","NEEDS_WORK").contains(a.get("STATUS")),"当前检查不可申诉");
  var task=ownTask(text(a,"TASK_ID"),u,true);require(!Set.of("SKIPPED","SKIP_PENDING").contains(task.get("STATUS")),"当前任务已跳过或正在申请跳过");currentSubmission(task,text(a,"SUBMISSION_ID"),u);
  db.update("UPDATE study_checkpoint_attempts SET status='APPEALED',appeal=? WHERE group_id=? AND id=? AND user_id=?",r.reason(),group,id,u);event(text(a,"TASK_ID"),u,"APPEAL",r.reason(),id);return Api.of(Map.of("status","APPEALED"));});}
 @PostMapping("/reviews/{id}/complete") Api<?> review(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Review r){String u=member(h);String next=day(r.next_day());require(next.compareTo(today())>0,"下次复习日期应晚于今天");return tx.execute(t->{lockMember(u);var rows=db.queryForList("SELECT * FROM study_reviews WHERE group_id=? AND id=? AND user_id=? FOR UPDATE",group,id,u);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"复习记录不存在");db.update("INSERT INTO study_review_history VALUES(?,?,?,?,?,?,?)",group,id(),id,u,r.reflection(),next,now());db.update("UPDATE study_reviews SET status='SCHEDULED',due_day=? WHERE group_id=? AND id=? AND user_id=?",next,group,id,u);return Api.of(Map.of("next_day",next));});}
 @PutMapping("/reviews/{id}/sharing") Api<?> sharing(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@RequestBody Sharing s){String u=member(h);if(db.update("UPDATE study_reviews SET shared=? WHERE group_id=? AND id=? AND user_id=?",s.shared(),group,id,u)==0)throw Api.error(HttpStatus.NOT_FOUND,"复习记录不存在");return Api.of(Map.of("shared",s.shared()));}
 @GetMapping("/shared-reviews") Api<?> sharedReviews(@RequestHeader(value="Authorization",required=false)String h){member(h);return Api.of(db.queryForList("SELECT r.id,r.reason,t.title,t.problem_key,m.nickname FROM study_reviews r JOIN study_tasks t ON r.group_id=t.group_id AND r.task_id=t.id JOIN members m ON r.user_id=m.user_id WHERE r.group_id=? AND r.shared=TRUE AND m.revoked=FALSE",group));}
 @GetMapping("/reviews/{id}/history") Api<?> reviewHistory(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String u=member(h);return Api.of(db.queryForList("SELECT * FROM study_review_history WHERE group_id=? AND review_id=? AND user_id=? ORDER BY created_at DESC",group,id,u));}
 @PostMapping("/tasks/{id}/help") Api<?> help(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Help r){String u=member(h);return tx.execute(t->{lockMember(u);ownTask(id,u,true);String discussion=id(),help=id();db.update("INSERT INTO discussions VALUES(?,?,?,?,?)",discussion,u,r.title(),r.body(),now());db.update("INSERT INTO event_log(event_id,kind,payload,created_at) VALUES(?,?,?,?)",id(),"discussion_created",encode(Map.of("id",discussion,"author_id",u,"title",r.title(),"body",r.body())),now());db.update("INSERT INTO study_help VALUES(?,?,?,?,?,?,'OPEN',?)",group,help,id,u,discussion,"",now());return Api.of(Map.of("id",help,"discussion_id",discussion));});}
 @PostMapping("/help/{id}/resolve") Api<?> resolve(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@Valid @RequestBody Resolution r){String u=member(h);if(db.update("UPDATE study_help SET resolution=?,status='RESOLVED' WHERE group_id=? AND id=? AND user_id=? AND status='OPEN'",r.understanding(),group,id,u)==0)throw Api.error(HttpStatus.NOT_FOUND,"待解决求助不存在");return Api.of(Map.of("status","RESOLVED"));}
 @GetMapping("/weekly") Api<?> weekly(@RequestHeader(value="Authorization",required=false)String h){String u=member(h);String from=LocalDate.now(LearningController.ZONE).minusDays(6).toString();var tasks=db.queryForList("SELECT * FROM study_tasks WHERE group_id=? AND user_id=?",group,u);
  long onTime=0,late=0,unfinished=0;
  for(var task:tasks){
   if("COMPLETED".equals(task.get("STATUS"))){String finished=text(task,"COMPLETED_AT");if(finished.isBlank())continue;
    String completedDay=Instant.parse(finished).atZone(LearningController.ZONE).toLocalDate().toString();
    if(completedDay.compareTo(from)<0||completedDay.compareTo(today())>0)continue;
    if(completedDay.compareTo(text(task,"ORIGINAL_DEADLINE"))<=0)onTime++;else late++;
   }else if(!"SKIPPED".equals(task.get("STATUS"))&&text(task,"DEADLINE").compareTo(from)>=0&&text(task,"DEADLINE").compareTo(today())<=0)unfinished++;
  }
  return Api.of(Map.of("from",from,"to",today(),"on_time",onTime,"late",late,"unfinished",unfinished,"completion_scope","last_seven_days","blockers",db.queryForList("SELECT * FROM study_events WHERE group_id=? AND user_id=? AND created_at>=? AND kind IN ('DEFER','REPLACE','SKIP_REQUEST','CHECKPOINT_REVIEW') ORDER BY created_at DESC",group,u,LocalDate.parse(from).atStartOfDay(LearningController.ZONE).toInstant().toString())));

 }
 @PostMapping("/retrospectives") Api<?> retrospective(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Retrospective r){String u=member(h),id=id();db.update("INSERT INTO study_retrospectives VALUES(?,?,?,?,?,?,?)",group,id,u,r.body(),r.next_plan(),r.confirmed(),now());return Api.of(Map.of("id",id));}
 @GetMapping("/resources") Api<?> resources(@RequestHeader(value="Authorization",required=false)String h){member(h);return Api.of(db.queryForList("SELECT * FROM study_resources WHERE group_id=? ORDER BY created_at DESC",group));}
 @PostMapping("/resources") Api<?> resource(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Resource r){String u="NOTICE".equals(r.kind())?leader(h):member(h),id=id();db.update("INSERT INTO study_resources VALUES(?,?,?,?,?,?,?)",group,id,u,r.title(),r.kind(),r.body(),now());return Api.of(Map.of("id",id));}
}
