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
    private final TokenCodec tokens; private final LocalGroupDeployer deployer;
    public GroupCreationController(LocalGroupDeployer deployer,JdbcTemplate db,@Value("${identity.public-key}") String key){this.deployer=deployer;this.db=db;tokens=new TokenCodec(key,null);}
    public record Creation(@NotBlank @Size(max=80) String name,@NotBlank @Size(max=500) String description,@jakarta.validation.constraints.Pattern(regexp="PUBLIC|PRIVATE") String visibility){}
    private String account(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=?",Integer.class,id)==0)throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");return id;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Api<?> create(@RequestHeader(value="Authorization",required=false) String h,@Valid @RequestBody Creation r){String user=account(h),id=UUID.randomUUID().toString();String visibility=r.visibility()==null?"PUBLIC":r.visibility();db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at,visibility) VALUES(?,?,?,?,?,?,?)",id,user,r.name(),r.description(),"PENDING",Instant.now().toString(),visibility);return Api.of(Map.of("id",id,"status","PENDING","visibility",visibility));}
    @GetMapping
    Api<?> own(@RequestHeader(value="Authorization",required=false) String h){String user=account(h);return Api.of(db.queryForList("SELECT r.id,r.name,r.description,r.status,r.visibility,r.deployment_status,r.deployment_error,r.deployed_at,r.created_at,COALESCE(g.id,remote.id) AS group_id FROM group_requests r LEFT JOIN deployed_groups g ON g.request_id=r.id LEFT JOIN remote_groups remote ON remote.request_id=r.id WHERE r.requested_by=? ORDER BY r.created_at DESC",user));}
    @PostMapping("/{id}/deploy") @Transactional
    Api<?> deploy(@RequestHeader(value="Authorization",required=false) String h,@PathVariable String id){
        String user=account(h);if(!deployer.enabled())throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"本机部署器未启用");int changed=db.update("UPDATE group_requests SET deployment_status='DEPLOYING' WHERE id=? AND requested_by=? AND status='APPROVED' AND deployment_status IN ('NOT_DEPLOYED','FAILED')",id,user);
        if(changed==0){var rows=db.queryForList("SELECT status,deployment_status FROM group_requests WHERE id=? AND requested_by=?",id,user);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"申请不存在");throw Api.error(HttpStatus.CONFLICT,"申请尚未批准或已经部署");}

        return Api.of(Map.of("id",id,"deployment_status","DEPLOYING","message","已提交本机部署任务"));
    }
    @GetMapping("/{id}/join-key")
    Api<?> joinKey(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){
        var rows=db.queryForList("SELECT join_key FROM deployed_groups WHERE request_id=? AND owner_id=? AND visibility='PRIVATE' AND status='READY'",id,account(h));
        if(rows.isEmpty())rows=db.queryForList("SELECT join_key FROM remote_groups WHERE request_id=? AND owner_id=? AND visibility='PRIVATE'",id,account(h));
        if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"私密群组未部署或无权查看");
        return Api.of(Map.of("join_key",rows.getFirst().get("JOIN_KEY")));
    }
}
