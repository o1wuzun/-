package dev.algotogether.group.replication;

import dev.algotogether.contracts.Api;
import dev.algotogether.contracts.TokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Node credentials are independent, hashed, read-only and recheck membership on every request. */
@Service
public class NodeRegistry {
    private final JdbcTemplate db;
    private final TokenCodec identity;
    private final String groupId,owner;
    private final SecureRandom random=new SecureRandom();
    public NodeRegistry(JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${group.id}")String groupId,@Value("${group.owner-id}")String owner){this.db=db;identity=new TokenCodec(pub,null);this.groupId=groupId;this.owner=owner;}
    private String member(String header){String u=identity.verify(header);if(db.queryForObject("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",Integer.class,u)==0)throw Api.error(HttpStatus.NOT_FOUND,"没有该群组的成员权限");return u;}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional public Map<String,Object> enroll(String header,String name){
        String user=member(header),id=UUID.randomUUID().toString();byte[] bytes=new byte[32];random.nextBytes(bytes);
        String credential=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO replica_nodes VALUES(?,?,?,?,FALSE,?)",id,user,name,hash(credential),Instant.now().getEpochSecond());
        return Map.of("id",id,"group_id",groupId,"name",name,"credential",credential,"scope","events:read");
    }
    public List<?> list(String header){String user=member(header);return db.query("SELECT id,name,last_seen,revoked FROM replica_nodes WHERE user_id=? ORDER BY last_seen DESC",(r,n)->Map.of("id",r.getString("id"),"name",r.getString("name"),"last_seen",r.getLong("last_seen"),"revoked",r.getBoolean("revoked")),user);}
    @Transactional public void revoke(String header,String node){String actor=member(header);var rows=db.queryForList("SELECT user_id FROM replica_nodes WHERE id=?",node);if(rows.isEmpty()||!(rows.getFirst().get("USER_ID").equals(actor)||owner.equals(actor)))throw Api.error(HttpStatus.NOT_FOUND,"节点不存在或不可管理");db.update("UPDATE replica_nodes SET revoked=TRUE WHERE id=?",node);}
    @Transactional public Map<String,Object> read(String credential,long after){
        if(credential==null)throw Api.error(HttpStatus.UNAUTHORIZED,"缺少节点凭证");
        var nodes=db.queryForList("SELECT n.id FROM replica_nodes n JOIN members m ON m.user_id=n.user_id WHERE n.credential_hash=? AND n.revoked=FALSE AND m.revoked=FALSE",hash(credential));
        if(nodes.isEmpty())throw Api.error(HttpStatus.UNAUTHORIZED,"节点凭证无效或已撤销");
        db.update("UPDATE replica_nodes SET last_seen=? WHERE id=?",Instant.now().getEpochSecond(),nodes.getFirst().get("ID"));
        var events=db.query("SELECT seq,event_id,kind,payload,created_at FROM event_log WHERE seq>? ORDER BY seq LIMIT 101",(r,n)->Map.<String,Object>of("seq",r.getLong("seq"),"event_id",r.getString("event_id"),"kind",r.getString("kind"),"payload",r.getString("payload"),"created_at",r.getString("created_at")),after);
        boolean more=events.size()>100;var batch=more?events.subList(0,100):events;
        long next=batch.isEmpty()?after:((Number)batch.getLast().get("seq")).longValue();
        return Map.of("group_id",groupId,"events",batch,"next_cursor",next,"has_more",more);
    }
}
