package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class NotificationIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("identity.public-key",()->IdentityIntegrationTest.pub);r.add("identity.private-key",()->IdentityIntegrationTest.priv);r.add("spring.datasource.url",()->"jdbc:h2:mem:notification-test;DB_CLOSE_DELAY=-1");}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired PlatformTransactionManager transactions;@Autowired NotificationService notifications;
    String login(boolean admin)throws Exception{
        String email=UUID.randomUUID()+"@example.test";
        String data=json.writeValueAsString(java.util.Map.of("email",email,"nickname","测试成员","password","Local-test-123"));
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(data)).andExpect(status().isCreated());
        if(admin)db.update("UPDATE accounts SET role='ADMIN' WHERE email=?",email);
        return "Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(data)).andReturn().getResponse().getContentAsString()).path("data").path("access_token").asText();
    }
    @Test void approvalCreatesPrivateNotificationAndOutboxOnce()throws Exception{
        String member=login(false),other=login(false),admin=login(true);
        String id=json.readTree(mvc.perform(post("/api/v1/community/group-requests").header("Authorization",member).contentType("application/json").content("{\"name\":\"通知测试\",\"description\":\"测试\"}")).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/admin/group-requests/"+id).header("Authorization",member).contentType("application/json").content("{\"status\":\"APPROVED\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/group-requests/"+id).header("Authorization",admin).contentType("application/json").content("{\"status\":\"APPROVED\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/group-requests/"+id).header("Authorization",admin).contentType("application/json").content("{\"status\":\"REJECTED\"}")).andExpect(status().isNotFound());
        String notice=db.queryForObject("SELECT id FROM notifications WHERE request_id=?",String.class,id);
        mvc.perform(get("/api/v1/notifications").header("Authorization",member)).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].MAIL_STATUS").value("PENDING"));
        mvc.perform(get("/api/v1/notifications").header("Authorization",other)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",member)).andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(patch("/api/v1/notifications/"+notice+"/read").header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/notifications/"+notice+"/read").header("Authorization",member)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",member)).andExpect(jsonPath("$.data.count").value(0));
        mvc.perform(get("/api/v1/admin/mail-jobs").header("Authorization",member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/mail-jobs/"+notice+"/retry").header("Authorization",admin)).andExpect(status().isConflict());
        db.update("UPDATE mail_jobs SET status='FAILED',attempts=5 WHERE id=?",notice);
        mvc.perform(post("/api/v1/admin/mail-jobs/"+notice+"/retry").header("Authorization",admin)).andExpect(status().isOk());
        assertEquals(0,db.queryForObject("SELECT attempts FROM mail_jobs WHERE id=?",Integer.class,notice));
    }
    @Test void transactionRollsBackDecisionAndNotificationTogether(){
        String user=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
        db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES(?,?,?,?)",user,user+"@example.test","测试","unused");
        db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at) VALUES(?,?,?,?,'PENDING','today')",request,user,"rollback","test");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).execute(s->{db.update("UPDATE group_requests SET status='APPROVED' WHERE id=?",request);notifications.decision(request,"APPROVED");throw new IllegalStateException("simulate failure");}));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM group_requests WHERE id=?",String.class,request));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM notifications WHERE request_id=?",Integer.class,request));
    }
}
