package dev.algotogether.identity;
import dev.algotogether.contracts.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminController {
 private final NotificationService notifications; private final JdbcTemplate db; private final TokenCodec tokens;
 public AdminController(NotificationService notifications,JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${identity.private-key}")String priv){this.notifications=notifications;this.db=db;this.tokens=new TokenCodec(pub,priv);}
 private String admin(String h){String id=tokens.verify(h);var roles=db.queryForList("SELECT role FROM accounts WHERE id=?",id);if(roles.isEmpty()||!"ADMIN".equals(roles.getFirst().get("ROLE")))throw Api.error(HttpStatus.FORBIDDEN,"需要管理员权限");return id;}
 public record GroupRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String name,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String description){}
 public record RequestDecision(@jakarta.validation.constraints.NotBlank String status){}
 @GetMapping("/overview") Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(Map.of("users",db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class),"admins",db.queryForObject("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'",Integer.class),"configured_groups",2,"pending_group_requests",db.queryForObject("SELECT COUNT(*) FROM group_requests WHERE status='PENDING'",Integer.class)));}
 @GetMapping("/users") Api<?> users(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(db.queryForList("SELECT id,email,nickname,role,avatar_url FROM accounts ORDER BY email"));}
 @GetMapping("/group-requests") Api<?> requests(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(db.queryForList("SELECT id,name,description,status,visibility,created_at FROM group_requests ORDER BY created_at DESC"));}
 @PostMapping("/group-requests")
 Api<?> requestGroup(@RequestHeader(value="Authorization",required=false)String h,@jakarta.validation.Valid @RequestBody GroupRequest r){String user=admin(h);String id=UUID.randomUUID().toString();db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at) VALUES(?,?,?,?,?,?)",id,user,r.name(),r.description(),"PENDING",java.time.Instant.now().toString());return Api.of(Map.of("id",id,"status","PENDING"));}
 @org.springframework.transaction.annotation.Transactional
 @PatchMapping("/group-requests/{id}")
 Api<?> decide(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id,@jakarta.validation.Valid @RequestBody RequestDecision decision){admin(h);if(!Set.of("APPROVED","REJECTED").contains(decision.status()))throw Api.error(HttpStatus.BAD_REQUEST,"状态只能是 APPROVED 或 REJECTED");int changed=db.update("UPDATE group_requests SET status=? WHERE id=? AND status='PENDING'",decision.status(),id);if(changed==0)throw Api.error(HttpStatus.NOT_FOUND,"申请不存在或已经处理");notifications.decision(id,decision.status());return Api.of(Map.of("id",id,"status",decision.status()));}
}
