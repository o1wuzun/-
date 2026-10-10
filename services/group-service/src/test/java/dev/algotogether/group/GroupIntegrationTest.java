package dev.algotogether.group;
import dev.algotogether.contracts.TokenCodec;
import java.security.KeyPairGenerator;
import java.util.Base64;
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
class GroupIntegrationTest {
    static String pub,priv;
    static {try{var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();pub=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());priv=Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("identity.public-key",()->pub);r.add("group.join-key",()->"integration-test-join-key");r.add("group.owner-id",()->"owner");r.add("spring.datasource.url",()->"jdbc:h2:mem:group-test;DB_CLOSE_DELAY=-1");}
    @Autowired MockMvc mvc;
    @Test void membershipGatesContentAndRevocationStopsReads() throws Exception {
        var codec=new TokenCodec(pub,priv);String owner="Bearer "+codec.issue("owner"),user="Bearer "+codec.issue("learner");
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",user)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/group/join").header("Authorization",owner).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"群主\",\"host_name\":\"node-a\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"wrong-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/group/discussions").header("Authorization",user).contentType("application/json").content("{\"title\":\"二分不变量\",\"body\":\"保持左闭右开区间\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",owner)).andExpect(jsonPath("$.data[0].body").value("保持左闭右开区间"));
        mvc.perform(get("/api/v1/group/events").header("Authorization",owner)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(delete("/api/v1/group/members/learner").header("Authorization",owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/group/discussions").header("Authorization",user)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/group/join").header("Authorization",user).contentType("application/json").content("{\"join_key\":\"integration-test-join-key\",\"nickname\":\"学习者\",\"host_name\":\"node-b\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/group/members").header("Authorization",user+"tampered")).andExpect(status().isUnauthorized());
    }
}
