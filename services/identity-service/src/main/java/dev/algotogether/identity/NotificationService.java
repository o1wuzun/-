package dev.algotogether.identity;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcTemplate db;
    public NotificationService(JdbcTemplate db) { this.db = db; }

    /** Called inside the approval transaction: both records commit with the decision. */
    @Transactional
    public void decision(String requestId, String status) {
        var request = db.queryForMap("SELECT r.requested_by,r.name,a.email FROM group_requests r JOIN accounts a ON a.id=r.requested_by WHERE r.id=?", requestId);
        String id = UUID.randomUUID().toString();
        String title = "群组创建申请" + (status.equals("APPROVED") ? "已批准" : "已驳回");
        String body = "你申请的群组「" + request.get("NAME") + "」" + (status.equals("APPROVED")
                ? "已获批准。群组仍需部署独立服务与数据库；批准不代表群组已经可连接。部署完成后请在群组入口查看。"
                : "未获批准。你可以调整申请内容后重新申请。");
        db.update("INSERT INTO notifications(id,user_id,request_id,title,body,created_at,is_read) VALUES(?,?,?,?,?,?,FALSE)",
                id, request.get("REQUESTED_BY"), requestId, title, body, Instant.now().toString());
        db.update("INSERT INTO mail_jobs(id,notification_id,recipient,subject,body,status,attempts,publish_attempts,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',0,0,0,?)",
                id, id, request.get("EMAIL"), title, body, Instant.now().toString());
    }
}
