package dev.algotogether.identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc
class PublicAccessTest {
 static String pub,priv;static final String KEY="gateway-test-secret-"+"x".repeat(32);
 static{try{var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);var p=gen.generateKeyPair();pub=Base64.getEncoder().encodeToString(p.getPublic().getEncoded());priv=Base64.getEncoder().encodeToString(p.getPrivate().getEncoded());}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("identity.public-key",()->pub);r.add("identity.private-key",()->priv);r.add("spring.datasource.url",()->"jdbc:h2:mem:public-access-test;DB_CLOSE_DELAY=-1");r.add("public-access.gateway-key",()->KEY);}
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
 String login(String email)throws Exception{String data=json.writeValueAsString(Map.of("email",email,"password","Access-test-123","nickname","测试"));mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(data)).andExpect(status().isCreated());return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(data)).andReturn().getResponse().getContentAsString()).path("data").path("access_token").asText();}
 String admin;
 void policy(boolean enabled)throws Exception{mvc.perform(put("/api/v1/admin/public-access").header("Authorization","Bearer "+admin).contentType("application/json").content("{\"enabled\":"+enabled+"}")).andExpect(status().isOk());}
 com.fasterxml.jackson.databind.JsonNode invite()throws Exception{return json.readTree(mvc.perform(post("/api/v1/admin/public-access/invitations").header("Authorization","Bearer "+admin).contentType("application/json").content("{\"name\":\"测试设备\",\"hours\":24}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data");}
 void check(String session,boolean allowed)throws Exception{mvc.perform(post("/api/v1/internal/public-access/check").header("Authorization","Bearer "+KEY).contentType("application/json").content(json.writeValueAsString(Map.of("session",session,"device",false)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(allowed));}
 @Test void defaultClosedOneTimeInvitationsRevocationExpiryAndPrivateAdminAccess()throws Exception{
  String user=login("public-user@test.local");admin=login("public-admin@test.local");db.update("UPDATE accounts SET role='ADMIN' WHERE email='public-admin@test.local'");
  mvc.perform(get("/api/v1/admin/public-access").header("Authorization","Bearer "+user)).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/internal/public-access/check").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());check("",false);
  policy(true);check("",false);
  var invitation=invite();String raw=invitation.path("token").asText();assertFalse(db.queryForObject("SELECT token_hash FROM public_access_invites WHERE id=?",String.class,invitation.path("id").asText()).contains(raw));
  mvc.perform(get("/api/v1/admin/public-access").header("Authorization","Bearer "+admin)).andExpect(jsonPath("$.data.invitations[0].TOKEN_HASH").doesNotExist());
  final String redeemToken=raw;var pool=Executors.newFixedThreadPool(2);List<Future<org.springframework.mock.web.MockHttpServletResponse>> results=new ArrayList<>();
  try{for(int i=0;i<2;i++)results.add(pool.submit(()->mvc.perform(post("/api/v1/internal/public-access/exchange").header("Authorization","Bearer "+KEY).contentType("application/json").content(json.writeValueAsString(Map.of("token",redeemToken)))).andReturn().getResponse()));
   var a=results.get(0).get();var b=results.get(1).get();assertEquals(Set.of(200,403),Set.of(a.getStatus(),b.getStatus()));String session=json.readTree(a.getStatus()==200?a.getContentAsString():b.getContentAsString()).path("data").path("session").asText();check(session,true);
   mvc.perform(delete("/api/v1/admin/public-access/invitations/"+invitation.path("id").asText()).header("Authorization","Bearer "+admin)).andExpect(status().isOk());check(session,false);
  }finally{pool.shutdownNow();}
  invitation=invite();raw=invitation.path("token").asText();String session=json.readTree(mvc.perform(post("/api/v1/internal/public-access/exchange").header("Authorization","Bearer "+KEY).contentType("application/json").content(json.writeValueAsString(Map.of("token",raw)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session").asText();check(session,true);policy(false);check(session,false);policy(true);check(session,false);
  db.update("UPDATE public_access_invites SET expires_at=0");mvc.perform(post("/api/v1/internal/public-access/check").header("Authorization","Bearer "+KEY).contentType("application/json").content("{\"device\":true}")).andExpect(jsonPath("$.data.allowed").value(false));
  mvc.perform(get("/api/v1/admin/public-access").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
 }
}
