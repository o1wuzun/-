package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/study-overview")
public class StudyOverviewController {
 private final JdbcTemplate db;private final ObjectMapper json;private final GroupServiceLocator groups;private final TokenCodec tokens;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public StudyOverviewController(JdbcTemplate db,ObjectMapper json,GroupServiceLocator groups,@Value("${identity.public-key}")String key){this.db=db;this.json=json;this.groups=groups;tokens=new TokenCodec(key,null);}
 @GetMapping Api<?> summary(@RequestHeader(value="Authorization",required=false)String h){tokens.verify(h);var ids=new LinkedHashSet<String>(List.of("group-default","group-contest"));ids.addAll(db.queryForList("SELECT id FROM deployed_groups WHERE status='READY'",String.class));ids.addAll(db.queryForList("SELECT id FROM remote_groups",String.class));var result=new ArrayList<Map<String,Object>>();
  for(String id:ids){try{var response=http.send(HttpRequest.newBuilder(URI.create(groups.url(id)+"/api/v1/group/study/overview")).timeout(Duration.ofSeconds(3)).header("Authorization",h).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)continue;var data=json.readTree(response.body()).path("data");int done=0,total=data.path("tasks").size();for(var task:data.path("tasks"))if("COMPLETED".equals(task.path("STATUS").asText()))done++;result.add(Map.of("group_id",id,"completed",done,"total",total,"stage",data.path("plans").path(0).path("STAGE").asText("暂无阶段")));}catch(Exception ignored){}}
  return Api.of(Map.of("groups",result,"display_only",true,"note","仅展示可访问群组，离线或无权限的群组不计入；各组学习状态不合并"));
 }
 public record Copy(@NotBlank String source_group,@NotBlank String target_group,@NotBlank String plan_id){}
 @PostMapping("/copy-plan") Api<?> copy(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Copy c){tokens.verify(h);if(c.source_group().equals(c.target_group()))throw Api.error(HttpStatus.BAD_REQUEST,"请选择另一个目标群组");try{
  var source=http.send(HttpRequest.newBuilder(URI.create(groups.url(c.source_group())+"/api/v1/group/study/plans/"+UUID.fromString(c.plan_id())+"/export")).timeout(Duration.ofSeconds(8)).header("Authorization",h).GET().build(),HttpResponse.BodyHandlers.ofString());
  if(source.statusCode()!=200)throw Api.error(HttpStatus.FORBIDDEN,"无权读取源群组计划或群组不可用");
  var target=http.send(HttpRequest.newBuilder(URI.create(groups.url(c.target_group())+"/api/v1/group/study/plans")).timeout(Duration.ofSeconds(8)).header("Authorization",h).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(json.readTree(source.body()).path("data")))).build(),HttpResponse.BodyHandlers.ofString());
  if(target.statusCode()!=200)throw Api.error(HttpStatus.FORBIDDEN,"复制失败：发布到目标群组需要目标群组组长权限");return Api.of(json.readTree(target.body()).path("data"));
 }catch(org.springframework.web.server.ResponseStatusException e){throw e;}catch(Exception e){throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"跨群组复制暂不可用，未复制任何成员进度");}}
}
