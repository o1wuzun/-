package dev.algotogether.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** A bundle contains only this group's credentials and the identity PUBLIC key. */
@RestController @RequestMapping("/api/v1/community/group-requests")
public class RemoteGroupDeployment {
 private final JdbcTemplate db;private final ObjectMapper json;private final TokenCodec tokens;private final String publicKey;private final TransactionTemplate tx;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 @Value("${deployment.remote.public-url:}") private String configuredUrl;
 @Value("${deployment.remote.relay-url:http://127.0.0.1:8095}") private String internalRelay;
 @Value("${deployment.remote.project-root:.}") private String projectRoot;
 private Path path(String relative){return Path.of(projectRoot).resolve(relative);}
 public RemoteGroupDeployment(JdbcTemplate db,ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager,@Value("${identity.public-key}")String key){this.db=db;this.json=json;this.tx=new TransactionTemplate(manager);publicKey=key;tokens=new TokenCodec(key,null);}
 private String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
 private Map<String,Object> owned(String h,String id){var rows=db.queryForList("SELECT * FROM remote_groups WHERE request_id=? AND owner_id=?",id,tokens.verify(h));if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"远程部署不存在或无权访问");return rows.getFirst();}
 private String publicUrl() throws Exception {
  String url=configuredUrl;
  if(url==null||url.isBlank()){var file=path("data/public-processes.json");if(Files.exists(file))url=json.readTree(Files.readString(file)).path("url").asText();}
  if(url==null||url.isBlank())throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"请先启动公网隧道，再生成远程部署包");
  URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"公网中继地址必须为 HTTPS 地址");
  return url.replaceAll("/$","");
 }
 private void register(Map<String,Object> row)throws Exception{
  var file=path("data/gateway-key.txt");if(!Files.exists(file))throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"内部中继注册尚未配置，请重新启动主机服务");
  var req=HttpRequest.newBuilder(URI.create(internalRelay+"/api/v1/internal/relay/"+row.get("ID"))).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+Files.readString(file).trim()).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("credential",row.get("RELAY_CREDENTIAL"))))).build();
  try{if(http.send(req,HttpResponse.BodyHandlers.discarding()).statusCode()!=200)throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"中继注册失败，请重启更新后的公网服务");}
  catch(IOException e){throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"中继服务未运行，请先启动公网服务");}
 }
 @PostMapping("/{id}/remote-deploy")
 Api<?> prepare(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id)throws Exception{
  String user=tokens.verify(h);publicUrl();
  tx.executeWithoutResult(t->{
   var existing=db.queryForList("SELECT id FROM remote_groups WHERE request_id=? AND owner_id=?",id,user);if(!existing.isEmpty())return;
   int changed=db.update("UPDATE group_requests SET deployment_status='REMOTE_PENDING',deployment_error=NULL WHERE id=? AND requested_by=? AND status='APPROVED' AND deployment_status IN ('NOT_DEPLOYED','FAILED') AND NOT EXISTS (SELECT 1 FROM deployed_groups WHERE request_id=?)",id,user,id);
   if(changed==0)throw Api.error(HttpStatus.CONFLICT,"申请尚未批准、已部署或不属于你");
   var row=db.queryForMap("SELECT name,description,visibility FROM group_requests WHERE id=?",id);
   db.update("INSERT INTO remote_groups(id,request_id,owner_id,name,description,visibility,join_key,relay_credential) VALUES(?,?,?,?,?,?,?,?)","remote-"+id,id,user,row.get("NAME"),row.get("DESCRIPTION"),row.get("VISIBILITY"),secret(),secret());
  });
  var row=owned(h,id);register(row);return Api.of(Map.of("group_id",row.get("ID"),"deployment_status","REMOTE_PENDING","message","已注册中继，请下载部署包并在目标电脑启动"));
 }
 @GetMapping("/{id}/remote-status")
 Api<?> status(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id){
  var row=owned(h,id);boolean online=false;String message="等待目标电脑启动";
  try{register(row);var response=http.send(HttpRequest.newBuilder(URI.create(internalRelay+"/api/v1/relay/"+row.get("ID")+"/status")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());online=response.statusCode()==200&&json.readTree(response.body()).path("data").path("online").asBoolean();}catch(Exception e){message="中继尚未就绪，请检查公网服务";}
  if(online){db.update("UPDATE group_requests SET deployment_status='DEPLOYED',deployment_error=NULL,deployed_at=COALESCE(deployed_at,?) WHERE id=?",Instant.now().toString(),id);message="远程主机已连接";}
  return Api.of(Map.of("group_id",row.get("ID"),"online",online,"message",message));
 }
 @GetMapping("/{id}/remote-bundle")
 ResponseEntity<byte[]> bundle(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String id)throws Exception{
  var row=owned(h,id);String url=publicUrl();
  var group=path("services/group-service/target/group-service-0.1.0-SNAPSHOT.jar");var tunnel=path("services/tunnel-client/target/tunnel-client-0.1.0-SNAPSHOT.jar");
  if(!Files.exists(group)||!Files.exists(tunnel))throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"请先构建群组服务和连接端");register(row);
  var config=new LinkedHashMap<String,Object>();for(String key:List.of("ID","NAME","DESCRIPTION","VISIBILITY","JOIN_KEY","OWNER_ID"))config.put(key.toLowerCase(Locale.ROOT),row.get(key));config.put("relay_credential",row.get("RELAY_CREDENTIAL"));config.put("relay_url",url);config.put("identity_public_key",publicKey);
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){
   add(zip,"group.jar",group);add(zip,"tunnel.jar",tunnel);for(String name:List.of("start.ps1","stop.ps1","start.cmd","README.txt"))add(zip,name,path("scripts/remote-group/"+name));zip.putNextEntry(new ZipEntry("group-config.json"));zip.write(json.writeValueAsBytes(config));zip.closeEntry();
  }
  return ResponseEntity.ok().header("Content-Disposition","attachment; filename=remote-group-"+id+".zip").header("Cache-Control","no-store").contentType(MediaType.APPLICATION_OCTET_STREAM).body(out.toByteArray());
 }
 private void add(ZipOutputStream zip,String name,Path path)throws IOException{zip.putNextEntry(new ZipEntry(name));Files.copy(path,zip);zip.closeEntry();}
}
