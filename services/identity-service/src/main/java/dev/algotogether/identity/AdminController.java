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
 @GetMapping("/overview") Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){admin(h);return Api.of(Map.of("users",db.queryForObject("SELECT COUNT(*) FROM accounts",Integer.class),"admins",db.queryForObject("SELECT COUNT(*) FROM accounts WHERE role='ADMIN'",Integer.class),"configured_groups",2));}
}
