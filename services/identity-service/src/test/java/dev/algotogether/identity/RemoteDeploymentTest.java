package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.algotogether.contracts.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RemoteDeploymentTest {
 @Test void approvedOwnerDownloadsIsolatedBundleAndConnectionUpdatesDeployment(@org.junit.jupiter.api.io.TempDir Path root)throws Exception{
  var data=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();var db=new JdbcTemplate(data);
  var relay=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var online=new AtomicBoolean(false);var available=new AtomicBoolean(false);
  relay.createContext("/",exchange->{
   int code=200;String body="{\"data\":{\"online\":"+online.get()+"}}";
   if(exchange.getRequestURI().getPath().contains("/internal/")){assertEquals("Bearer "+"m".repeat(32),exchange.getRequestHeaders().getFirst("Authorization"));assertTrue(new ObjectMapper().readTree(exchange.getRequestBody()).path("credential").asText().length()>=32);if(!available.get())code=503;}
   byte[] bytes=body.getBytes();exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });relay.start();
  try{
   var json=new ObjectMapper();var controller=new RemoteGroupDeployment(db,json,new DataSourceTransactionManager(data),IdentityIntegrationTest.pub);
   ReflectionTestUtils.setField(controller,"configuredUrl","https://community.example.com");ReflectionTestUtils.setField(controller,"internalRelay","http://127.0.0.1:"+relay.getAddress().getPort());ReflectionTestUtils.setField(controller,"projectRoot",root.toString());
   Files.createDirectories(root.resolve("data"));Files.writeString(root.resolve("data/gateway-key.txt"),"m".repeat(32));
   for(String file:List.of("services/group-service/target/group-service-0.1.0-SNAPSHOT.jar","services/tunnel-client/target/tunnel-client-0.1.0-SNAPSHOT.jar","scripts/remote-group/start.ps1","scripts/remote-group/stop.ps1","scripts/remote-group/start.cmd","scripts/remote-group/README.txt")){Path path=root.resolve(file);Files.createDirectories(path.getParent());Files.writeString(path,"fixture");}
   var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();var codec=new TokenCodec(IdentityIntegrationTest.pub,IdentityIntegrationTest.priv);
   for(String user:List.of("owner","other"))db.update("INSERT INTO accounts(id,email,nickname,password_hash,role) VALUES(?,?,?,'unused','USER')",user,user+"@example.com",user);
   String id=UUID.randomUUID().toString(),pending=UUID.randomUUID().toString(),local=UUID.randomUUID().toString();
   for(String rid:List.of(id,pending,local))db.update("INSERT INTO group_requests(id,requested_by,name,description,status,created_at,visibility,deployment_status) VALUES(?,'owner','远程学习组','测试',?,'now','PRIVATE',?)",rid,rid.equals(pending)?"PENDING":"APPROVED",rid.equals(local)?"DEPLOYING":"NOT_DEPLOYED");
   String auth="Bearer "+codec.issue("owner"),other="Bearer "+codec.issue("other");String base="/api/v1/community/group-requests/";
   mvc.perform(post(base+id+"/remote-deploy")).andExpect(status().isUnauthorized());
   mvc.perform(post(base+id+"/remote-deploy").header("Authorization",other)).andExpect(status().isConflict());
   mvc.perform(post(base+pending+"/remote-deploy").header("Authorization",auth)).andExpect(status().isConflict());
   mvc.perform(post(base+local+"/remote-deploy").header("Authorization",auth)).andExpect(status().isConflict());
   db.update("UPDATE group_requests SET deployment_status='FAILED' WHERE id=?",local);
   db.update("INSERT INTO deployed_groups(id,request_id,owner_id,name,description,visibility,port,join_key,status) VALUES(?,?,'owner','local','failed','PRIVATE',8199,'local-secret-key','STARTING')","local-"+local,local);
   mvc.perform(post(base+local+"/remote-deploy").header("Authorization",auth)).andExpect(status().isConflict());
   mvc.perform(post(base+id+"/remote-deploy").header("Authorization",auth)).andExpect(status().isServiceUnavailable());
   assertEquals("REMOTE_PENDING",db.queryForObject("SELECT deployment_status FROM group_requests WHERE id=?",String.class,id));
   available.set(true);mvc.perform(post(base+id+"/remote-deploy").header("Authorization",auth)).andExpect(status().isOk());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM remote_groups",Integer.class));
   mvc.perform(get(base+id+"/remote-bundle").header("Authorization",other)).andExpect(status().isNotFound());
   var response=mvc.perform(get(base+id+"/remote-bundle").header("Authorization",auth)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
   var entries=new HashSet<String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){entries.add(entry.getName());if(entry.getName().equals("group-config.json")){var config=json.readTree(zip.readAllBytes());assertEquals("remote-"+id,config.path("id").asText());assertEquals("owner",config.path("owner_id").asText());assertEquals(IdentityIntegrationTest.pub,config.path("identity_public_key").asText());assertFalse(config.has("identity_private_key"));assertNotEquals(config.path("join_key").asText(),config.path("relay_credential").asText());assertFalse(config.toString().contains("m".repeat(32)));}}}
   assertEquals(Set.of("group.jar","tunnel.jar","start.ps1","stop.ps1","start.cmd","README.txt","group-config.json"),entries);
   mvc.perform(get(base+id+"/remote-status").header("Authorization",auth)).andExpect(jsonPath("$.data.online").value(false));
   online.set(true);mvc.perform(get(base+id+"/remote-status").header("Authorization",auth)).andExpect(jsonPath("$.data.online").value(true));assertEquals("DEPLOYED",db.queryForObject("SELECT deployment_status FROM group_requests WHERE id=?",String.class,id));
   online.set(false);mvc.perform(get(base+id+"/remote-status").header("Authorization",auth)).andExpect(jsonPath("$.data.online").value(false));
  }finally{relay.stop(0);data.shutdown();}
 }
}
