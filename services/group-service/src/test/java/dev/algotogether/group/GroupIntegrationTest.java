package dev.algotogether.group;
import dev.algotogether.contracts.TokenCodec;
import java.security.KeyPairGenerator;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GroupIntegrationTest {
    static String pub,priv;
    static {try{var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();pub=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());priv=Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("identity.public-key",()->pub);r.add("group.join-key",()->"integration-test-join-key");r.add("group.owner-id",()->"owner");r.add("spring.datasource.url",()->"jdbc:h2:mem:group-test");}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Test void directedRepliesAndPrivateMessagesReachOnlyTheirRecipient()throws Exception{
        var codec=new TokenCodec(pub,priv);String a="Bearer "+codec.issue("target-a"),b="Bearer "+codec.issue("target-b"),c="Bearer "+codec.issue("target-c");
        for(String h:new String[]{a,b,c})mvc.perform(post("/api/v1/group/join").header("Authorization",h).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"同伴\",\"host_name\":\"device\"}")).andExpect(status().isOk());
        String post=json.readTree(mvc.perform(post("/api/v1/group/discussions").header("Authorization",a).contentType("application/json").content("{\"title\":\"公式讨论\",\"body\":\"$n^2$\"}")).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();String path="/api/v1/group/discussions/"+post+"/comments";
        String first=json.readTree(mvc.perform(post(path).header("Authorization",b).contentType("application/json").content("{\"body\":\"$O(n)$\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(post(path).header("Authorization",c).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("body","回复给 B：$$x^2$$","parent_comment_id",first,"reply_to_user_id","target-b")))).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/group/replies/me").header("Authorization",b)).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].body").value("回复给 B：$$x^2$$"));
        mvc.perform(get("/api/v1/group/replies/me").header("Authorization",a)).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].body").value("$O(n)$"));
        mvc.perform(get("/api/v1/group/replies/me").header("Authorization",c)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(path).header("Authorization",a)).andExpect(jsonPath("$.data[1].reply_to_user_id").value("target-b")).andExpect(jsonPath("$.data[1].parent_comment_id").value(first));
        mvc.perform(post(path).header("Authorization",a).contentType("application/json").content(json.writeValueAsString(java.util.Map.of("body","wrong","parent_comment_id",first,"reply_to_user_id","target-c")))).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization",a).contentType("application/json").content("{\"body\":\"wrong\",\"reply_to_user_id\":\"outsider\"}")).andExpect(status().isNotFound());
        String message=json.readTree(mvc.perform(post("/api/v1/group/messages").header("Authorization",b).contentType("application/json").content("{\"recipient_id\":\"target-c\",\"body\":\"PRIVATE-FORMULA $x$\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(get("/api/v1/group/messages/me").header("Authorization",a)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/group/messages/me").header("Authorization",c)).andExpect(jsonPath("$.data[0].sender_id").value("target-b"));
        mvc.perform(patch("/api/v1/group/messages/"+message+"/read").header("Authorization",a)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/group/messages/"+message+"/read").header("Authorization",c)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/group/messages/me").header("Authorization",b)).andExpect(jsonPath("$.data[0].is_read").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM event_log WHERE payload LIKE '%PRIVATE-FORMULA%'",Integer.class));
        db.update("UPDATE members SET revoked=TRUE WHERE user_id='target-c'");
        mvc.perform(get("/api/v1/group/messages/me").header("Authorization",c)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/group/messages").header("Authorization",b).contentType("application/json").content("{\"recipient_id\":\"target-c\",\"body\":\"revoked\"}")).andExpect(status().isNotFound());
    }
    @Test void membershipGatesContentAndRevocationStopsReads() throws Exception {
        var codec=new TokenCodec(pub,priv);String owner="Bearer "+codec.issue("owner"),user="Bearer "+codec.issue("learner");
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",user)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/group/join").header("Authorization",owner).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"群主\",\"host_name\":\"node-a\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"wrong-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/group/discussions").header("Authorization",user).contentType("application/json").content("{\"title\":\"二分不变量\",\"body\":\"保持左闭右开区间\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",owner)).andExpect(jsonPath("$.data[0].body").value("保持左闭右开区间"));
        mvc.perform(get("/api/v1/group/events").header("Authorization",owner)).andExpect(jsonPath("$.data.length()").value(3));
        var enrolled=mvc.perform(post("/api/v1/group/nodes").header("Authorization",user).contentType("application/json").content("{\"name\":\"成员副本节点\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String nodeToken=json.readTree(enrolled).path("data").path("credential").asText();
        String nodeId=json.readTree(enrolled).path("data").path("id").asText();
        mvc.perform(get("/api/v1/group/nodes").header("Authorization",user)).andExpect(jsonPath("$.data[0].credential").doesNotExist());
        mvc.perform(get("/api/v1/group/replication/events").header("X-Node-Credential",nodeToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.events.length()").value(3)).andExpect(jsonPath("$.data.next_cursor").value(3));
        mvc.perform(get("/api/v1/group/replication/events").param("after","3").header("X-Node-Credential",nodeToken)).andExpect(jsonPath("$.data.events.length()").value(0));
        mvc.perform(get("/api/v1/group/replication/events").header("X-Node-Credential","invalid")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/group/nodes/"+nodeId).header("Authorization",user)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/group/replication/events").header("X-Node-Credential",nodeToken)).andExpect(status().isUnauthorized());
        var second=mvc.perform(post("/api/v1/group/nodes").header("Authorization",user).contentType("application/json").content("{\"name\":\"第二副本节点\"}")).andReturn().getResponse().getContentAsString();
        String secondToken=json.readTree(second).path("data").path("credential").asText();
        mvc.perform(delete("/api/v1/group/members/learner").header("Authorization",owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/group/replication/events").header("X-Node-Credential",secondToken)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/group/members").header("Authorization",user+"tampered")).andExpect(status().isUnauthorized());
    }
    @Test void membersCanReplyButOutsidersCannot() throws Exception {
        var codec=new TokenCodec(pub,priv);String a="Bearer "+codec.issue("reply-a"),b="Bearer "+codec.issue("reply-b"),outsider="Bearer "+codec.issue("outsider");
        for(String h:new String[]{a,b})mvc.perform(post("/api/v1/group/join").header("Authorization",h).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"同伴\",\"host_name\":\"device\"}")).andExpect(status().isOk());
        var response=mvc.perform(post("/api/v1/group/discussions").header("Authorization",a).contentType("application/json").content("{\"title\":\"回复场景\",\"body\":\"如何二分\"}")).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("data").path("id").asText();String path="/api/v1/group/discussions/"+id+"/comments";
        mvc.perform(post(path).header("Authorization",b).contentType("application/json").content("{\"body\":\"先定义不变量\"}")).andExpect(status().isCreated());
        mvc.perform(get(path).header("Authorization",a)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].body").value("先定义不变量"));
        mvc.perform(get(path).header("Authorization",outsider)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization",b).contentType("application/json").content("{\"body\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/group/discussions/missing/comments").header("Authorization",a)).andExpect(status().isNotFound());
    }

}
