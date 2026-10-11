package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.security.SecureRandom;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.*;

@Service @EnableScheduling
public class LocalGroupDeployer {
 private final JdbcTemplate db;private final ObjectMapper json;private final boolean enabled;private final String shell;
 private final Path root=Path.of("").toAbsolutePath();
 public LocalGroupDeployer(JdbcTemplate db,ObjectMapper json,@Value("${deployment.local.enabled:false}")boolean enabled,@Value("${deployment.local.shell:pwsh}")String shell){this.db=db;this.json=json;this.enabled=enabled;this.shell=shell;}
 public boolean enabled(){return enabled;}
 @jakarta.annotation.PostConstruct public void recover(){if(enabled){db.update("UPDATE group_requests SET deployment_status='DEPLOYING' WHERE deployment_status='DEPLOYED' AND id IN (SELECT request_id FROM deployed_groups)");db.update("UPDATE deployed_groups SET status='STARTING' WHERE status='READY'");}}
 @Scheduled(fixedDelay=5000)
 public synchronized void work(){
  if(!enabled)return;
  for(var r:db.queryForList("SELECT * FROM group_requests WHERE deployment_status='DEPLOYING' ORDER BY created_at LIMIT 1")){
   String request=(String)r.get("ID");
   try{
    var existing=db.queryForList("SELECT * FROM deployed_groups WHERE request_id=?",request);
    String id="local-"+request;int port;
    String secret;
    if(existing.isEmpty()){
     port=8100;while(port<=8199&&db.queryForObject("SELECT COUNT(*) FROM deployed_groups WHERE port=?",Integer.class,port)>0)port++;
     if(port>8199)throw new IllegalStateException("No ports available");
     secret=secret();
     db.update("INSERT INTO deployed_groups(id,request_id,owner_id,name,description,visibility,port,join_key,status) VALUES(?,?,?,?,?,?,?,?,'STARTING')",id,request,r.get("REQUESTED_BY"),r.get("NAME"),r.get("DESCRIPTION"),r.get("VISIBILITY"),port,secret);
    }else{port=((Number)existing.getFirst().get("PORT")).intValue();secret=(String)existing.getFirst().get("JOIN_KEY");}
    var mysql=json.readTree(Files.readString(root.resolve("data/mysql/project-config.json")));
    String database="group_"+request.replace("-","");String user="g_"+request.replace("-","").substring(0,20);
    Path directory=root.resolve("data/deployed-groups");Files.createDirectories(directory);Path config=directory.resolve(id+".json");
    Map<String,Object> saved=Files.exists(config)?json.readValue(Files.readString(config),Map.class):new LinkedHashMap<>();
    String password=saved.containsKey("db_password")?(String)saved.get("db_password"):secret();
    String url="jdbc:mysql://127.0.0.1:"+mysql.path("port").asInt()+"/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8";
    saved.putAll(Map.of("id",id,"port",port,"name",r.get("NAME"),"description",r.get("DESCRIPTION"),"visibility",r.get("VISIBILITY"),"owner_id",r.get("REQUESTED_BY"),"join_key",secret,"db_url",url,"db_user",user,"db_password",password));
    Files.writeString(config,json.writeValueAsString(saved));
    String admin=mysql.path("admin_password").asText();String adminUrl=url.replace("/"+database+"?","/?");
    try(var connection=DriverManager.getConnection(adminUrl,"root",admin);var statement=connection.createStatement()){
     statement.execute("CREATE DATABASE IF NOT EXISTS `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
     statement.execute("CREATE USER IF NOT EXISTS '"+user+"'@'127.0.0.1' IDENTIFIED BY '"+password+"'");
     statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON `"+database+"`.* TO '"+user+"'@'127.0.0.1'");
    }
    Flyway.configure().dataSource(url,"root",admin).locations("filesystem:"+root.resolve("services/group-service/src/main/resources/db/migration/mysql")).load().migrate();
    boolean ready=false;try{var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/group")).timeout(java.time.Duration.ofSeconds(2)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());ready=response.statusCode()==200&&id.equals(json.readTree(response.body()).path("data").path("id").asText());}catch(Exception ignored){}
    if(!ready){var process=new ProcessBuilder(shell,"-NoProfile","-File",root.resolve("scripts/deploy-local-group.ps1").toString(),"-Configuration",config.toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(id+".deploy.log").toFile()).start();if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0){process.destroy();throw new IllegalStateException("Deployment failed");}}
    new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource())).executeWithoutResult(tx->{
     db.update("UPDATE deployed_groups SET status='READY' WHERE id=?",id);
     db.update("UPDATE group_requests SET deployment_status='DEPLOYED',deployed_at=?,deployment_error=NULL WHERE id=?",Instant.now().toString(),request);
     db.update("UPDATE notifications SET body=CONCAT(body,?),is_read=FALSE,delivered_at=NULL WHERE request_id=? AND body NOT LIKE '%群组部署成功%'",
       "\n群组部署成功，可以从群组入口进入。私密群组的加入密钥可在自己的申请旁查看。",request);
    });
   }catch(Exception e){db.update("UPDATE group_requests SET deployment_status='FAILED',deployment_error='本地部署失败，请检查部署日志和端口后重试' WHERE id=?",request);}
  }
 }
 private String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
