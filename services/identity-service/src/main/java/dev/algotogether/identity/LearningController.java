package dev.algotogether.identity;

import dev.algotogether.contracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;

/** Personal records are never copied into group replicas. OJ AC is evidence, not mastery. */
@RestController @RequestMapping("/api/v1/learning")
public class LearningController {
 static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private final JdbcTemplate db;private final TokenCodec tokens;private final ObjectMapper json;
 private final TransactionTemplate tx;private final String cfBase;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
 public LearningController(JdbcTemplate db,ObjectMapper json,PlatformTransactionManager manager,
  @Value("${identity.public-key}")String key,@Value("${learning.codeforces-url:https://codeforces.com/api}")String cfBase){this.db=db;this.json=json;tokens=new TokenCodec(key,null);tx=new TransactionTemplate(manager);this.cfBase=cfBase;}
 private String user(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?",Integer.class,id)==0)throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");return id;}
 public record Binding(@Pattern(regexp="Codeforces") @NotBlank String provider,@Pattern(regexp="[a-zA-Z0-9_.-]{3,24}") @NotBlank String handle){}
 public record Point(@NotBlank @Size(max=100)String title,@Pattern(regexp="[0-9]{1,7}[A-Z][0-9]?") @NotBlank String problem_key,@NotBlank @Size(max=500)String criterion){}
 public record Checkin(@NotBlank String point_id,@Min(1) @Max(1440) int minutes,@NotBlank @Size(max=2000)String reflection){}
 @GetMapping("/overview") Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){String u=user(h);return Api.of(Map.of("today",LocalDate.now(ZONE).toString(),"timezone",ZONE.toString(),"binding",db.queryForList("SELECT provider,handle,version FROM oj_bindings WHERE user_id=?",u),"points",db.queryForList("SELECT * FROM learning_points WHERE user_id=? ORDER BY created_at DESC",u),"checkins",history(db,u),"plans",db.queryForList("SELECT learning_day,body,created_at FROM learning_plans WHERE user_id=? ORDER BY learning_day DESC LIMIT 7",u),"total_score",db.queryForObject("SELECT COALESCE(SUM(score),0) FROM daily_checkins WHERE user_id=? AND settled=TRUE",Long.class,u)));}
 static List<Map<String,Object>> history(JdbcTemplate db,String u){return db.queryForList("SELECT c.*,p.title,p.problem_key,p.criterion FROM daily_checkins c JOIN learning_points p ON p.id=c.point_id WHERE c.user_id=? ORDER BY c.learning_day DESC LIMIT 30",u);}
 @PutMapping("/oj-binding") Api<?> bind(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Binding b){String u=user(h);tx.executeWithoutResult(t->{db.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE",String.class,u);if(db.update("UPDATE oj_bindings SET provider=?,handle=?,version=version+1 WHERE user_id=?",b.provider(),b.handle(),u)==0)db.update("INSERT INTO oj_bindings VALUES(?,?,?,1)",u,b.provider(),b.handle());db.update("UPDATE daily_checkins SET verification='PENDING',evidence_id=NULL,evidence_handle=NULL,binding_version=NULL WHERE user_id=? AND settled=FALSE",u);});return Api.of(Map.of("provider",b.provider(),"handle",b.handle()));}
 @PostMapping("/points") @ResponseStatus(HttpStatus.CREATED)
 Api<?> point(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Point p){String u=user(h),id=UUID.randomUUID().toString();db.update("INSERT INTO learning_points VALUES(?,?,?,?,?,?)",id,u,p.title(),p.problem_key(),p.criterion(),Instant.now().toString());return Api.of(Map.of("id",id));}
 @PostMapping("/checkins") @ResponseStatus(HttpStatus.CREATED)
 Api<?> checkin(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Checkin c){String u=user(h);if(db.queryForObject("SELECT COUNT(*) FROM learning_points WHERE id=? AND user_id=?",Integer.class,c.point_id(),u)==0)throw Api.error(HttpStatus.NOT_FOUND,"学习点不存在");if(db.queryForObject("SELECT COUNT(*) FROM oj_bindings WHERE user_id=?",Integer.class,u)==0)throw Api.error(HttpStatus.CONFLICT,"请先绑定 OJ 账号");String id=UUID.randomUUID().toString();try{db.update("INSERT INTO daily_checkins(id,user_id,learning_day,point_id,minutes,reflection,verification,settled,score,created_at) VALUES(?,?,?,?,?,?,'PENDING',FALSE,0,?)",id,u,LocalDate.now(ZONE).toString(),c.point_id(),c.minutes(),c.reflection(),Instant.now().toString());}catch(DuplicateKeyException e){throw Api.error(HttpStatus.CONFLICT,"今日已打卡，请验证并结算已有记录");}return Api.of(Map.of("id",id,"verification","PENDING"));}
 private Map<String,Object> own(String id,String u){var rows=db.queryForList("SELECT c.*,p.problem_key FROM daily_checkins c JOIN learning_points p ON p.id=c.point_id WHERE c.id=? AND c.user_id=?",id,u);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"打卡不存在");return rows.getFirst();}
 @PostMapping("/checkins/{id}/verify")
 Api<?> verify(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String u=user(h);var c=own(id,u);if(Boolean.TRUE.equals(c.get("SETTLED")))return Api.of(c);var bindings=db.queryForList("SELECT * FROM oj_bindings WHERE user_id=?",u);if(bindings.isEmpty())throw Api.error(HttpStatus.CONFLICT,"请绑定 OJ");var binding=bindings.getFirst();String evidence=null;
  try{String handle=(String)binding.get("HANDLE");var response=http.send(HttpRequest.newBuilder(URI.create(cfBase+"/user.status?handle="+handle+"&from=1&count=1000")).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException();var data=json.readTree(response.body());if(!"OK".equals(data.path("status").asText()))throw new IllegalStateException();
   for(var submission:data.path("result")){var problem=submission.path("problem");String key=problem.path("contestId").asText()+problem.path("index").asText();boolean author=false;for(var member:submission.path("author").path("members"))if(handle.equalsIgnoreCase(member.path("handle").asText()))author=true;String day=Instant.ofEpochSecond(submission.path("creationTimeSeconds").asLong()).atZone(ZONE).toLocalDate().toString();if(author&&"OK".equals(submission.path("verdict").asText())&&key.equals(c.get("PROBLEM_KEY"))&&day.equals(c.get("LEARNING_DAY"))){evidence=submission.path("id").asText();break;}}
    if(evidence==null&&data.path("result").size()>=1000&&Instant.ofEpochSecond(data.path("result").path(999).path("creationTimeSeconds").asLong()).atZone(ZONE).toLocalDate().toString().compareTo(c.get("LEARNING_DAY").toString())>=0)throw Api.error(HttpStatus.CONFLICT,"最近 1000 条提交未覆盖完整打卡日期，请稍后重试或选择更近期记录");
  }catch(org.springframework.web.server.ResponseStatusException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"OJ 验证被中断");}catch(Exception e){throw Api.error(HttpStatus.BAD_GATEWAY,"OJ 服务不可用或账号不存在，打卡保留待验证状态");}
  String found=evidence;tx.executeWithoutResult(t->{db.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE",String.class,u);Long current=db.queryForObject("SELECT version FROM oj_bindings WHERE user_id=?",Long.class,u);if(current.longValue()!=((Number)binding.get("VERSION")).longValue())throw Api.error(HttpStatus.CONFLICT,"绑定已改变，请重新验证");db.update("UPDATE daily_checkins SET verification=?,evidence_id=?,evidence_handle=?,binding_version=? WHERE id=? AND user_id=? AND settled=FALSE",found==null?"NO_MATCH":"VERIFIED",found,binding.get("HANDLE"),current,id,u);});return Api.of(own(id,u));}
 @PostMapping("/checkins/{id}/settle")
 Api<?> settle(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String u=user(h);return tx.execute(t->{db.queryForObject("SELECT id FROM accounts WHERE id=? FOR UPDATE",String.class,u);var c=own(id,u);if(Boolean.TRUE.equals(c.get("SETTLED")))return Api.of(c);if("PENDING".equals(c.get("VERIFICATION")))throw Api.error(HttpStatus.CONFLICT,"请先完成 OJ 验证；验证失败不能结算");Long version=db.queryForObject("SELECT version FROM oj_bindings WHERE user_id=?",Long.class,u);if(c.get("BINDING_VERSION")==null||version.longValue()!=((Number)c.get("BINDING_VERSION")).longValue())throw Api.error(HttpStatus.CONFLICT,"请重新验证 OJ 绑定");int score=1;if("VERIFIED".equals(c.get("VERIFICATION"))&&db.queryForObject("SELECT COUNT(*) FROM learning_awards WHERE user_id=? AND problem_key=?",Integer.class,u,c.get("PROBLEM_KEY"))==0){db.update("INSERT INTO learning_awards VALUES(?,?,?)",u,c.get("PROBLEM_KEY"),id);score+=2;}db.update("UPDATE daily_checkins SET settled=TRUE,score=? WHERE id=?",score,id);return Api.of(own(id,u));});}
}
