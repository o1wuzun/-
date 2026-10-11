package dev.algotogether.group;
import dev.algotogether.contracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/group")
public class GroupController {
    private final JdbcTemplate db; private final TokenCodec tokens; private final String id,name,joinKey,owner,visibility,description;
    private final ObjectMapper json;
    public GroupController(JdbcTemplate db,ObjectMapper json,@Value("${identity.public-key}")String publicKey,@Value("${group.id}")String id,@Value("${group.name}")String name,@Value("${group.join-key}")String joinKey,@Value("${group.owner-id}")String owner,@Value("${group.visibility:PUBLIC}")String visibility,@Value("${group.description:独立算法学习群组}")String description){
        if(joinKey.length()<16)throw new IllegalArgumentException("GROUP_JOIN_KEY must contain at least 16 characters");
        this.db=db;this.json=json;this.tokens=new TokenCodec(publicKey,null);this.id=id;this.name=name;this.joinKey=joinKey;this.owner=owner;this.visibility=visibility;this.description=description;
    }
    private String member(String header){String user=tokens.verify(header);if(db.queryForObject("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",Integer.class,user)==0)throw Api.error(HttpStatus.NOT_FOUND,"尚未加入该群组或成员资格已撤销");return user;}
    private void event(String kind,Object payload){try{db.update("INSERT INTO event_log(event_id,kind,payload,created_at) VALUES(?,?,?,?)",UUID.randomUUID().toString(),kind,json.writeValueAsString(payload),Instant.now().toString());}catch(Exception e){throw new IllegalStateException(e);}}
    public record Join(String join_key,@NotBlank @Size(max=40)String nickname,@NotBlank @Size(max=80)String host_name){}
    public record Discussion(@NotBlank @Size(max=100)String title,@NotBlank @Size(max=20000)String body){}
    public record Comment(@NotBlank @Size(max=5000) String body){}
    private void discussionExists(String post){if(db.queryForObject("SELECT COUNT(*) FROM discussions WHERE id=?",Integer.class,post)==0)throw Api.error(HttpStatus.NOT_FOUND,"讨论不存在");}
    @GetMapping("/discussions/{post}/comments")
    Api<?> comments(@PathVariable String post,@RequestHeader(value="Authorization",required=false)String h){member(h);discussionExists(post);return Api.of(db.query("SELECT c.id,c.body,c.created_at,m.nickname FROM comments c JOIN members m ON c.author_id=m.user_id WHERE c.discussion_id=? ORDER BY c.created_at,c.id LIMIT 500",(r,n)->Map.of("id",r.getString("id"),"body",r.getString("body"),"created_at",r.getString("created_at"),"nickname",r.getString("nickname")),post));}
    @PostMapping("/discussions/{post}/comments") @Transactional @ResponseStatus(HttpStatus.CREATED)
    Api<?> comment(@PathVariable String post,@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Comment r){String user=member(h);discussionExists(post);String id=UUID.randomUUID().toString(),time=Instant.now().toString();db.update("INSERT INTO comments VALUES(?,?,?,?,?)",id,post,user,r.body(),time);event("comment_created",Map.of("id",id,"discussion_id",post,"author_id",user,"body",r.body(),"created_at",time));return Api.of(Map.of("id",id));}
    @GetMapping Api<?> info(){return Api.of(Map.of("id",id,"name",name,"description",description,"visibility",visibility,"mode","independent-group-service"));}
    @PostMapping("/join") @Transactional
    Api<?> join(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Join r){
        String user=tokens.verify(h);
        if(!"PUBLIC".equals(visibility)&&!MessageDigest.isEqual(joinKey.getBytes(StandardCharsets.UTF_8),(r.join_key()==null?"":r.join_key()).getBytes(StandardCharsets.UTF_8)))throw Api.error(HttpStatus.FORBIDDEN,"联网密钥无效");
        var rows=db.queryForList("SELECT revoked FROM members WHERE user_id=?",user);
        if(!rows.isEmpty()&&Boolean.TRUE.equals(rows.getFirst().get("REVOKED")))throw Api.error(HttpStatus.FORBIDDEN,"成员资格已撤销，请联系群主");
        dev.algotogether.persistence.PortableUpsert.execute(db,"UPDATE members SET nickname=?,host_name=?,last_seen=? WHERE user_id=? AND revoked=FALSE",new Object[]{r.nickname(),r.host_name(),Instant.now().getEpochSecond(),user},"INSERT INTO members(user_id,nickname,host_name,last_seen,revoked) VALUES(?,?,?,?,FALSE)",new Object[]{user,r.nickname(),r.host_name(),Instant.now().getEpochSecond()});
        event("member_joined",Map.of("user_id",user,"nickname",r.nickname()));return Api.of(Map.of("group_id",id,"user_id",user));
    }
    @PostMapping("/heartbeat") Api<?> heartbeat(@RequestHeader(value="Authorization",required=false)String h){String u=member(h);db.update("UPDATE members SET last_seen=? WHERE user_id=?",Instant.now().getEpochSecond(),u);return Api.of(Map.of("synced_at",Instant.now().toString()));}
    @GetMapping("/members") Api<?> members(@RequestHeader(value="Authorization",required=false)String h){member(h);var rows=db.queryForList("SELECT user_id,nickname,host_name,last_seen FROM members WHERE revoked=FALSE ORDER BY last_seen DESC");return Api.of(rows.stream().map(r->Map.of("user_id",r.get("USER_ID"),"nickname",r.get("NICKNAME"),"host_name",r.get("HOST_NAME"),"online",((Number)r.get("LAST_SEEN")).longValue()>Instant.now().getEpochSecond()-90)).toList());}
    @DeleteMapping("/members/{user}") @Transactional @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable String user,@RequestHeader(value="Authorization",required=false)String h){String actor=member(h);if(!actor.equals(owner))throw Api.error(HttpStatus.FORBIDDEN,"仅群主可以移除成员");if(user.equals(owner))throw Api.error(HttpStatus.CONFLICT,"不能移除群主");db.update("UPDATE members SET revoked=TRUE WHERE user_id=?",user);event("member_revoked",Map.of("user_id",user));}
    @GetMapping("/discussions") Api<?> discussions(@RequestHeader(value="Authorization",required=false)String h){member(h);return Api.of(db.query("SELECT d.id,d.title,d.body,d.created_at,m.nickname FROM discussions d JOIN members m ON d.author_id=m.user_id ORDER BY d.created_at DESC LIMIT 100",(r,n)->Map.of("id",r.getString("id"),"title",r.getString("title"),"body",r.getString("body"),"created_at",r.getString("created_at"),"nickname",r.getString("nickname"))));}
    @PostMapping("/discussions") @Transactional @ResponseStatus(HttpStatus.CREATED)
    Api<?> publish(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Discussion r){String user=member(h),post=UUID.randomUUID().toString();db.update("INSERT INTO discussions VALUES(?,?,?,?,?)",post,user,r.title(),r.body(),Instant.now().toString());event("discussion_created",Map.of("id",post,"author_id",user,"title",r.title(),"body",r.body()));return Api.of(Map.of("id",post));}
    // Read-only event cursor for future node enrollment/replication. Not a peer-write endpoint.
    @GetMapping("/events") Api<?> events(@RequestHeader(value="Authorization",required=false)String h,@RequestParam(defaultValue="0")long after){member(h);return Api.of(db.query("SELECT seq,event_id,kind,payload,created_at FROM event_log WHERE seq>? ORDER BY seq LIMIT 100",(r,n)->Map.of("seq",r.getLong("seq"),"event_id",r.getString("event_id"),"kind",r.getString("kind"),"payload",r.getString("payload"),"created_at",r.getString("created_at")),after));}
}
