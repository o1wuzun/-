package dev.algotogether.group;

import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Private member messages are never exported in the group discussion event log. */
@RestController @RequestMapping("/api/v1/group/messages")
public class MemberMessages {
 private final JdbcTemplate db;private final TokenCodec tokens;
 public MemberMessages(JdbcTemplate db,@Value("${identity.public-key}")String key){this.db=db;tokens=new TokenCodec(key,null);}
 private String member(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",Integer.class,id)==0)throw Api.error(HttpStatus.FORBIDDEN,"请先加入当前群组");return id;}
 public record Message(@NotBlank @Size(max=36)String recipient_id,@NotBlank @Size(max=5000)String body){}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) Api<?> send(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Message m){
  String sender=member(h);if(sender.equals(m.recipient_id()))throw Api.error(HttpStatus.BAD_REQUEST,"请选择其他成员");
  if(db.queryForObject("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",Integer.class,m.recipient_id())==0)throw Api.error(HttpStatus.NOT_FOUND,"收件人不是当前群组成员");
  String id=UUID.randomUUID().toString();db.update("INSERT INTO member_messages(id,sender_id,recipient_id,body,created_at,is_read) VALUES(?,?,?,?,?,FALSE)",id,sender,m.recipient_id(),m.body(),Instant.now().toString());return Api.of(Map.of("id",id));
 }
 @GetMapping("/me") Api<?> inbox(@RequestHeader(value="Authorization",required=false)String h){String user=member(h);return Api.of(db.query("SELECT x.*,s.nickname AS sender_nickname,r.nickname AS recipient_nickname FROM member_messages x JOIN members s ON s.user_id=x.sender_id JOIN members r ON r.user_id=x.recipient_id WHERE sender_id=? OR recipient_id=? ORDER BY created_at DESC LIMIT 100",(rs,n)->Map.of("id",rs.getString("id"),"sender_id",rs.getString("sender_id"),"recipient_id",rs.getString("recipient_id"),"sender_nickname",rs.getString("sender_nickname"),"recipient_nickname",rs.getString("recipient_nickname"),"body",rs.getString("body"),"created_at",rs.getString("created_at"),"is_read",rs.getBoolean("is_read")),user,user));}
 @PatchMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) void read(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){String user=member(h);if(db.update("UPDATE member_messages SET is_read=TRUE WHERE id=? AND recipient_id=?",id,user)==0)throw Api.error(HttpStatus.NOT_FOUND,"消息不存在或不属于你");}
}
