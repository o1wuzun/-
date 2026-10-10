package dev.algotogether.identity;

import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Group creation belongs to every authenticated account, independently of platform roles. */
@RestController @RequestMapping("/api/v1/community/group-requests")
public class GroupCreationController {
    private final JdbcTemplate db;
    private final TokenCodec tokens;
    public GroupCreationController(JdbcTemplate db,@Value("${identity.public-key}") String key){this.db=db;tokens=new TokenCodec(key,null);}
    public record Creation(@NotBlank @Size(max=80) String name,@NotBlank @Size(max=500) String description){}
    private String account(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?",Integer.class,id)==0)throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");return id;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Api<?> create(@RequestHeader(value="Authorization",required=false) String h,@Valid @RequestBody Creation r){String user=account(h),id=UUID.randomUUID().toString();db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at) VALUES(?,?,?,?,?,?)",id,user,r.name(),r.description(),"PENDING",Instant.now().toString());return Api.of(Map.of("id",id,"status","PENDING"));}
    @GetMapping
    Api<?> own(@RequestHeader(value="Authorization",required=false) String h){String user=account(h);return Api.of(db.queryForList("SELECT id,name,description,status,created_at FROM group_requests WHERE requested_by=? ORDER BY created_at DESC",user));}
}
