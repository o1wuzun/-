package dev.algotogether.identity;
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
class IdentityIntegrationTest {
    static String pub,priv;
    static {try{var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var keys=gen.generateKeyPair();pub=Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());priv=Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded());}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("identity.public-key",()->pub);r.add("identity.private-key",()->priv);r.add("spring.datasource.url",()->"jdbc:h2:mem:identity-test;DB_CLOSE_DELAY=-1");}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @Test void directoryContainsIndependentGroups() throws Exception {
        mvc.perform(get("/api/v1/community/groups")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].proxy_path").value("/groups/group-default")).andExpect(jsonPath("$.data[1].id").value("group-contest"));
    }
    @Test void registrationAndAuthentication()throws Exception{
        String data="{\"email\":\"student@example.com\",\"nickname\":\"算法同伴\",\"password\":\"test-password-123\"}";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(data)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(data)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\":\"student@example.com\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        var response=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(data)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=json.readTree(response).path("data").path("access_token").asText();
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("student@example.com"));
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/users/me/integrations").header("Authorization","Bearer "+token).contentType("application/json").content("{\"oj_provider\":\"Codeforces\",\"oj_api_key\":\"oj-secret\",\"advisor_provider\":\"test-model\",\"advisor_api_key\":\"model-secret\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.integrations.advisor_api_key").doesNotExist()).andExpect(jsonPath("$.data.integrations.advisor_key_configured").value(true));
        mvc.perform(put("/api/v1/users/me/integrations").header("Authorization","Bearer "+token).contentType("application/json").content("{\"oj_provider\":\"Codeforces\",\"oj_api_key\":\"\",\"advisor_provider\":\"new-model\",\"advisor_api_key\":\"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.integrations.advisor_key_configured").value(true));

        String creation="{\"name\":\"普通用户学习组\",\"description\":\"用户创建权限测试\"}";
        mvc.perform(post("/api/v1/community/group-requests").contentType("application/json").content(creation)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/community/group-requests").header("Authorization","Bearer "+token).contentType("application/json").content(creation)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(get("/api/v1/community/group-requests").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].NAME").value("普通用户学习组"));
        mvc.perform(get("/api/v1/admin/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
}
