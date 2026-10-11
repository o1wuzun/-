package dev.algotogether.identity;

import dev.algotogether.contracts.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final JdbcTemplate db;
    private final TokenCodec tokens;
    private final boolean enabled;
    public NotificationController(JdbcTemplate db,@Value("${identity.public-key}") String key,
                                  @Value("${notifications.enabled:false}") boolean enabled) {
        this.db=db; this.tokens=new TokenCodec(key,null); this.enabled=enabled;
    }
    private String account(String header,boolean admin) {
        String id=tokens.verify(header);
        var rows=db.queryForList("SELECT role FROM accounts WHERE id=?",id);
        if(rows.isEmpty())throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");
        if(admin&&!"ADMIN".equals(rows.getFirst().get("ROLE")))throw Api.error(HttpStatus.FORBIDDEN,"需要管理员权限");
        return id;
    }
    @GetMapping("/notifications")
    Api<?> list(@RequestHeader(value="Authorization",required=false)String h) {
        String id=account(h,false);
        return Api.of(db.queryForList("SELECT n.*,n.delivered_at AS delivery_status,m.status AS mail_status FROM notifications n LEFT JOIN mail_jobs m ON m.notification_id=n.id WHERE n.user_id=? ORDER BY n.created_at DESC LIMIT 100",id));
    }
    @GetMapping("/notifications/unread-count")
    Api<?> unread(@RequestHeader(value="Authorization",required=false)String h) {
        return Api.of(Map.of("count",db.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=FALSE",Long.class,account(h,false))));
    }
    @PatchMapping("/notifications/{id}/read")
    Api<?> read(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id) {
        if(db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?",id,account(h,false))==0)
            throw Api.error(HttpStatus.NOT_FOUND,"通知不存在");
        return Api.of(Map.of("read",true));
    }
    @GetMapping("/admin/mail-jobs")
    Api<?> jobs(@RequestHeader(value="Authorization",required=false)String h) {
        account(h,true);
        return Api.of(Map.of("enabled",enabled,"jobs",db.queryForList("SELECT id,recipient,subject,status,attempts,publish_attempts,next_attempt_at,last_error,created_at,sent_at FROM mail_jobs ORDER BY created_at DESC LIMIT 100")));
    }
    @PostMapping("/admin/mail-jobs/{id}/retry")
    Api<?> retry(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id) {
        account(h,true);
        if(db.update("UPDATE mail_jobs SET status='PENDING',attempts=0,next_attempt_at=0,last_error=NULL WHERE id=? AND status IN ('FAILED','CONFIG_REQUIRED')",id)==0)
            throw Api.error(HttpStatus.CONFLICT,"仅失败或缺少配置的邮件可以重试");
        return Api.of(Map.of("status","PENDING"));
    }
}
