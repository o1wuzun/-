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
import org.springframework.transaction.annotation.Transactional;

/** Group creation belongs to every authenticated account, independently of platform roles. */
@RestController @RequestMapping("/api/v1/community/group-requests")
public class GroupCreationController {
    private final JdbcTemplate db;
    private final TokenCodec tokens;
    public GroupCreationController(JdbcTemplate db,@Value("${identity.public-key}") String key){this.db=db;tokens=new TokenCodec(key,null);}
    public record Creation(@NotBlank @Size(max=80) String name,@NotBlank @Size(max=500) String description,@jakarta.validation.constraints.Pattern(regexp="PUBLIC|PRIVATE") String visibility){}
    private String account(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?",Integer.class,id)==0)throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");return id;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Api<?> create(@RequestHeader(value="Authorization",required=false) String h,@Valid @RequestBody Creation r){String user=account(h),id=UUID.randomUUID().toString();String visibility=r.visibility()==null?"PUBLIC":r.visibility();db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at,visibility) VALUES(?,?,?,?,?,?,?)",id,user,r.name(),r.description(),"PENDING",Instant.now().toString(),visibility);return Api.of(Map.of("id",id,"status","PENDING","visibility",visibility));}
    @GetMapping
    Api<?> own(@RequestHeader(value="Authorization",required=false) String h){String user=account(h);return Api.of(db.queryForList("SELECT id,name,description,status,visibility,deployment_status,deployed_at,created_at FROM group_requests WHERE requested_by=? ORDER BY created_at DESC",user));}
    @PostMapping("/{id}/deploy") @Transactional
    Api<?> deploy(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
        String user=account(h);int changed=db.update("UPDATE group_requests SET deployment_status='DEPLOYING' WHERE id=? AND requested_by=? AND status='APPROVED' AND deployment_status='NOT_DEPLOYED'",id,user);
        if(changed==0){var rows=db.queryForList("SELECT status,deployment_status FROM group_requests WHERE id=? AND requested_by=?",id,user);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"申请不存在");throw Api.error(HttpStatus.CONFLICT,"申请尚未批准或已经部署");}
        db.update("UPDATE group_requests SET deployment_status='DEPLOYED',deployed_at=? WHERE id=? AND deployment_status='DEPLOYING'",Instant.now().toString(),id);
        return Api.of(Map.of("id",id,"deployment_status","DEPLOYED","message","本地群组部署配置已生成，请刷新群组入口查看"));
    }
}
