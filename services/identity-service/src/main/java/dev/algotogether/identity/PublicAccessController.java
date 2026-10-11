package dev.algotogether.identity;

import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Separate perimeter invitations. These never replace account or group authorization. */
@RestController
public class PublicAccessController {
 private final JdbcTemplate db; private final TokenCodec tokens; private final String gatewayKey;
 public PublicAccessController(JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${identity.private-key}")String priv,@Value("${public-access.gateway-key:}")String key){this.db=db;tokens=new TokenCodec(pub,priv);gatewayKey=key;}
 private void admin(String h){String id=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE id=? AND role='ADMIN'",Integer.class,id)==0)throw Api.error(HttpStatus.FORBIDDEN,"需要管理员权限");}
 private void gateway(String h){if(gatewayKey.length()<32||h==null||!MessageDigest.isEqual(("Bearer "+gatewayKey).getBytes(StandardCharsets.UTF_8),h.getBytes(StandardCharsets.UTF_8)))throw Api.error(HttpStatus.UNAUTHORIZED,"网关认证失败");}
 private static long now(){return Instant.now().getEpochSecond();}
 private static String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public record Policy(boolean enabled){}
 public record Invitation(@NotBlank @Size(max=80) String name,@Min(1) @Max(168) int hours){}
 public record Check(@Size(max=200) String session,boolean device){}
 public record Exchange(@NotBlank @Size(max=200) String token){}
 @GetMapping("/api/v1/admin/public-access")
 Api<?> state(@RequestHeader(value="Authorization",required=false)String h){admin(h);var policy=db.queryForMap("SELECT enabled,generation FROM public_access_policy WHERE id=1");int active=db.queryForObject("SELECT COUNT(*) FROM public_access_invites WHERE revoked=FALSE AND expires_at>?",Integer.class,now());return Api.of(Map.of("enabled",policy.get("ENABLED"),"effective",Boolean.TRUE.equals(policy.get("ENABLED")),"access_mode","OPEN","active_invitations",active,"invitations",db.queryForList("SELECT id,name,expires_at,created_at,consumed,revoked FROM public_access_invites ORDER BY created_at DESC")));}
 @PutMapping("/api/v1/admin/public-access") @Transactional
 Api<?> policy(@RequestHeader(value="Authorization",required=false)String h,@RequestBody Policy r){admin(h);db.queryForMap("SELECT generation FROM public_access_policy WHERE id=1 FOR UPDATE");db.update("UPDATE public_access_policy SET enabled=?,generation=generation+1 WHERE id=1",r.enabled());return state(h);}
 @PostMapping("/api/v1/admin/public-access/invitations") @ResponseStatus(HttpStatus.CREATED)
 Api<?> invite(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Invitation r){admin(h);String id=UUID.randomUUID().toString(),token=secret();long expires=now()+r.hours()*3600L;db.update("INSERT INTO public_access_invites(id,name,token_hash,expires_at,created_at,consumed,revoked) VALUES(?,?,?,?,?,FALSE,FALSE)",id,r.name(),hash(token),expires,now());return Api.of(Map.of("id",id,"token",token,"expires_at",expires));}
 @DeleteMapping("/api/v1/admin/public-access/invitations/{id}")
 Api<?> revoke(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){admin(h);if(db.update("UPDATE public_access_invites SET revoked=TRUE WHERE id=?",id)==0)throw Api.error(HttpStatus.NOT_FOUND,"邀请不存在");return Api.of(Map.of("revoked",true));}
 @PostMapping("/api/v1/internal/public-access/check")
 Api<?> check(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Check r){gateway(h);var p=db.queryForMap("SELECT enabled,generation FROM public_access_policy WHERE id=1");boolean enabled=Boolean.TRUE.equals(p.get("ENABLED"));boolean allowed=enabled;
  return Api.of(Map.of("enabled",enabled,"allowed",allowed));
 }
 @PostMapping("/api/v1/internal/public-access/exchange") @Transactional
 Api<?> exchange(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Exchange r){gateway(h);var p=db.queryForMap("SELECT enabled,generation FROM public_access_policy WHERE id=1 FOR UPDATE");if(!Boolean.TRUE.equals(p.get("ENABLED")))throw Api.error(HttpStatus.FORBIDDEN,"管理员尚未开启公网访问");var rows=db.queryForList("SELECT id,expires_at FROM public_access_invites WHERE token_hash=? AND revoked=FALSE AND consumed=FALSE AND expires_at>?",hash(r.token()),now());if(rows.isEmpty())throw Api.error(HttpStatus.FORBIDDEN,"邀请无效、已使用或已过期");var row=rows.getFirst();if(db.update("UPDATE public_access_invites SET consumed=TRUE WHERE id=? AND consumed=FALSE AND revoked=FALSE",row.get("ID"))!=1)throw Api.error(HttpStatus.FORBIDDEN,"邀请已失效");String token=secret();long expires=Math.min(((Number)row.get("EXPIRES_AT")).longValue(),now()+8*3600);db.update("DELETE FROM public_access_sessions WHERE expires_at<=?",now());db.update("INSERT INTO public_access_sessions(token_hash,invite_id,generation,expires_at) VALUES(?,?,?,?)",hash(token),row.get("ID"),p.get("GENERATION"),expires);return Api.of(Map.of("session",token,"max_age",expires-now()));}
}
