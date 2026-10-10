package dev.algotogether.identity;
import dev.algotogether.contracts.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminController {
 private final JdbcTemplate db; private final TokenCodec tokens;
 public AdminController(JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${identity.private-key}")String priv){this.db=db;this.tokens=new TokenCodec(pub,priv);}
 private String admin(String h){String id=tokens.verify(h);var roles=db.queryForList("SELECT role FROM accounts WHERE id=?",id);if(roles.isEmpty()||!"ADMIN".equals(roles.getFirst().get("ROLE")))throw Api.error(HttpStatus.FORBIDDEN,"需要管理员权限");return id;}
 public record GroupRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String name,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=500) String description){}
 @GetMapping("/overview") Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(Map.of("users",db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class),"admins",db.queryForObject("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'",Integer.class),"configured_groups",2,"pending_group_requests",db.queryForObject("SELECT COUNT(*) FROM group_requests WHERE status='PENDING'",Integer.class)));}
 @GetMapping("/users") Api<?> users(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(db.queryForList("SELECT id,email,nickname,role,avatar_url FROM accounts ORDER BY email"));}
 @GetMapping("/group-requests") Api<?> requests(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(db.queryForList("SELECT id,name,description,status,created_at FROM group_requests ORDER BY created_at DESC"));}
 @PostMapping("/group-requests")
 Api<?> requestGroup(@RequestHeader(value="Authorization",required=false)String h,@jakarta.validation.Valid @RequestBody GroupRequest r){String user=admin(h);String id=UUID.randomUUID().toString();db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at) VALUES(?,?,?,?,?,?)",id,user,r.name(),r.description(),"PENDING",java.time.Instant.now().toString());return Api.of(Map.of("id",id,"status","PENDING"));}
}
