package dev.algotogether.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import dev.algotogether.contracts.TunnelMessages.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

/** Outbound-only connector to one operator-configured source, not a general proxy. */
@RestController
public class TunnelWorker {
 private final String relay,group,credential,source;private final ObjectMapper json;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 private volatile String state="starting";private volatile long completed;
 public TunnelWorker(ObjectMapper json,@Value("${tunnel.relay-url}")String relay,@Value("${tunnel.group-id}")String group,@Value("${tunnel.credential}")String credential,@Value("${tunnel.source-url}")String source){
  this.json=json;this.relay=relay.replaceAll("/$","");this.group=group;this.credential=credential;this.source=source.replaceAll("/$","");
  if(!group.matches("[a-zA-Z0-9-]{1,80}")||credential.length()<24)throw new IllegalArgumentException("Invalid tunnel group or credential");
  URI uri=URI.create(relay);if(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())))throw new IllegalArgumentException("Public relay requires HTTPS; loopback HTTP is allowed for tests");
 }
 @Scheduled(fixedDelay=500)
 public void connect(){try{
  var info=http.send(HttpRequest.newBuilder(URI.create(source+"/api/v1/group")).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
  if(info.statusCode()!=200||!group.equals(json.readTree(info.body()).path("data").path("id").asText())){state="source_identity_mismatch";return;}
  var poll=http.send(HttpRequest.newBuilder(URI.create(relay+"/api/v1/tunnel/"+group+"/poll")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+credential).GET().build(),HttpResponse.BodyHandlers.ofString());
  if(poll.statusCode()!=200){state="relay_rejected";return;}
  state="connected";
  for(var item:json.readTree(poll.body()).path("data")){
   Request request=json.treeToValue(item,Request.class);String path=request.path().split("\\?",2)[0];
   if(!path.matches("/api/v1/group(?:/[a-zA-Z0-9_-]+)*")||!Set.of("GET","POST","PATCH","PUT","DELETE").contains(request.method()))throw new IllegalArgumentException("Invalid forwarded group request");
   var builder=HttpRequest.newBuilder(URI.create(source+request.path())).timeout(Duration.ofSeconds(8));
   request.headers().forEach((name,value)->{if(Set.of("Authorization","X-Node-Credential","Content-Type").contains(name))builder.header(name,value);});
   Reply reply;
   try{var response=http.send(builder.method(request.method(),HttpRequest.BodyPublishers.ofByteArray(request.body())).build(),HttpResponse.BodyHandlers.ofByteArray());if(response.body().length>262144)throw new IllegalStateException("Response too large");reply=new Reply(request.id(),response.statusCode(),"application/json",response.body());}
   catch(Exception e){reply=new Reply(request.id(),502,"application/json","{\"error\":{\"message\":\"群组源服务响应失败\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
   var sent=http.send(HttpRequest.newBuilder(URI.create(relay+"/api/v1/tunnel/"+group+"/replies")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+credential).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(reply))).build(),HttpResponse.BodyHandlers.discarding());
   if(sent.statusCode()==204)completed++;
  }
 }catch(InterruptedException e){Thread.currentThread().interrupt();state="interrupted";}catch(Exception e){state="disconnected";}}
 @GetMapping("/api/v1/tunnel/status") Api<?> status(){return Api.of(Map.of("group_id",group,"state",state,"completed",completed));}
}
