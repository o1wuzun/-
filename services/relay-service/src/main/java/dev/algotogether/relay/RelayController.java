package dev.algotogether.relay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import dev.algotogether.contracts.TunnelMessages.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Bounded reverse HTTP transport. Group services retain all membership authorization. */
@RestController
public class RelayController {
 private static class Channel {
  final BlockingQueue<Request> queue=new ArrayBlockingQueue<>(32);
  final Map<String,CompletableFuture<Reply>> pending=new ConcurrentHashMap<>();
  volatile long seen;
 }
 private final Map<String,String> credentials;private final Map<String,Channel> channels=new ConcurrentHashMap<>();
 private final long waitSeconds;
 public RelayController(ObjectMapper json,@Value("${relay.credentials}")String config,@Value("${relay.request-timeout-seconds:20}")long waitSeconds)throws Exception{
  this.waitSeconds=waitSeconds;
  credentials=Map.copyOf(json.readValue(config,new TypeReference<Map<String,String>>(){}));
  for(var entry:credentials.entrySet()){if(!entry.getKey().matches("[a-zA-Z0-9-]{1,80}")||entry.getValue().length()<24)throw new IllegalArgumentException("Relay credentials require valid group IDs and keys of at least 24 characters");channels.put(entry.getKey(),new Channel());}
 }
 private Channel channel(String group){var channel=channels.get(group);if(channel==null)throw Api.error(HttpStatus.NOT_FOUND,"中继群组未配置");return channel;}
 private Channel authenticate(String group,String h){var channel=channel(group);String expected="Bearer "+credentials.get(group);if(h==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),h.getBytes(StandardCharsets.UTF_8)))throw Api.error(HttpStatus.UNAUTHORIZED,"中继连接凭证无效");return channel;}
 @GetMapping("/api/v1/tunnel/{group}/poll")
 Api<?> poll(@PathVariable String group,@RequestHeader(value="Authorization",required=false)String h)throws InterruptedException{
  var channel=authenticate(group,h);channel.seen=Instant.now().getEpochSecond();Request request;
  do{request=channel.queue.poll(10,TimeUnit.SECONDS);}while(request!=null&&!channel.pending.containsKey(request.id()));
  return Api.of(request==null?List.of():List.of(request));
 }
 @PostMapping("/api/v1/tunnel/{group}/replies") @ResponseStatus(HttpStatus.NO_CONTENT)
 void reply(@PathVariable String group,@RequestHeader(value="Authorization",required=false)String h,@RequestBody Reply reply){var channel=authenticate(group,h);if(reply.id()==null||reply.id().isBlank()||reply.status()<200||reply.status()>599||reply.body()==null||reply.body().length>262144)throw Api.error(HttpStatus.BAD_REQUEST,"无效的中继响应");var pending=channel.pending.get(reply.id());if(pending==null)throw Api.error(HttpStatus.NOT_FOUND,"请求已超时或不属于此群组");pending.complete(reply);}
 @GetMapping("/api/v1/relay/{group}/status")
 Api<?> status(@PathVariable String group){var c=channel(group);return Api.of(Map.of("group_id",group,"online",Instant.now().getEpochSecond()-c.seen<30,"pending",c.pending.size()));}
 @RequestMapping(value={"/groups/{group}/api/v1/group","/groups/{group}/api/v1/group/**"},method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PATCH,RequestMethod.PUT,RequestMethod.DELETE})
 ResponseEntity<byte[]> forward(@PathVariable String group,HttpServletRequest incoming)throws Exception{
  var c=channel(group);if(Instant.now().getEpochSecond()-c.seen>=30)throw Api.error(HttpStatus.SERVICE_UNAVAILABLE,"群组主机离线，请稍后重试");
  String path=incoming.getRequestURI().substring(("/groups/"+group).length());
  if(!path.matches("/api/v1/group(?:/[a-zA-Z0-9_-]+)*"))throw Api.error(HttpStatus.BAD_REQUEST,"中继仅接受群组接口路径");
  if(incoming.getQueryString()!=null)path+="?"+incoming.getQueryString();
  byte[] body=incoming.getInputStream().readNBytes(65537);if(body.length>65536)throw Api.error(HttpStatus.PAYLOAD_TOO_LARGE,"请求内容过大");
  var headers=new HashMap<String,String>();for(String name:List.of("Authorization","X-Node-Credential","Content-Type")){String value=incoming.getHeader(name);if(value!=null)headers.put(name,value);}
  String id=UUID.randomUUID().toString();var request=new Request(id,incoming.getMethod(),path,headers,body);var future=new CompletableFuture<Reply>();
  c.pending.put(id,future);
  try{
   if(!c.queue.offer(request))throw Api.error(HttpStatus.TOO_MANY_REQUESTS,"群组请求队列已满，请重试");
   var reply=future.get(waitSeconds,TimeUnit.SECONDS);var out=new HttpHeaders();
   out.setContentType(MediaType.APPLICATION_JSON);out.setCacheControl("no-store");return new ResponseEntity<>(reply.body(),out,HttpStatusCode.valueOf(reply.status()));
  }catch(TimeoutException e){throw Api.error(HttpStatus.GATEWAY_TIMEOUT,"群组主机响应超时，请重试");}finally{c.pending.remove(id);c.queue.remove(request);}
 }
}
