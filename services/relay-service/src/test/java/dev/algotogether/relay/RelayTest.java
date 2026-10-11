package dev.algotogether.relay;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.TunnelMessages.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RelayTest {
 @Test void remoteRegistrationRequiresInternalCredentialAndSurvivesRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
  var controller=new RelayController(new ObjectMapper(),"{}",1);
  var keyField=RelayController.class.getDeclaredField("managementKey");keyField.setAccessible(true);keyField.set(controller,"m".repeat(32));
  var fileField=RelayController.class.getDeclaredField("registryFile");fileField.setAccessible(true);fileField.set(controller,dir.resolve("registry.json").toString());
  String group="remote-"+java.util.UUID.randomUUID();var config=new RelayController.Registration("r".repeat(32));
  assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.register(group,"Bearer wrong",config)).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.register("group-default","Bearer "+"m".repeat(32),config)).getStatusCode().value());
  controller.register(group,"Bearer "+"m".repeat(32),config);controller.register(group,"Bearer "+"m".repeat(32),config);
  assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.register(group,"Bearer "+"m".repeat(32),new RelayController.Registration("s".repeat(32)))).getStatusCode().value());
  var restored=new RelayController(new ObjectMapper(),"{}",1);fileField.set(restored,dir.resolve("registry.json").toString());restored.restoreRegistry();
  assertFalse((Boolean)((java.util.Map<?,?>)restored.status(group).data()).get("online"));
  assertEquals(401,assertThrows(ResponseStatusException.class,()->restored.poll(group,"Bearer wrong")).getStatusCode().value());
  var executor=Executors.newCachedThreadPool();try{
   var poll=executor.submit(()->restored.poll(group,"Bearer "+config.credential()));
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!(Boolean)((java.util.Map<?,?>)restored.status(group).data()).get("online")&&System.nanoTime()<deadline)Thread.sleep(5);
   var incoming=new MockHttpServletRequest("GET","/groups/"+group+"/api/v1/group");var forwarded=executor.submit(()->restored.forward(group,incoming));
   var request=(Request)((List<?>)poll.get(2,TimeUnit.SECONDS).data()).getFirst();restored.reply(group,"Bearer "+config.credential(),new Reply(request.id(),200,"application/json","{}".getBytes()));assertEquals(200,forwarded.get(2,TimeUnit.SECONDS).getStatusCode().value());
  }finally{executor.shutdownNow();}
 }
 @Test void correlatesRequestsRejectsWrongGroupAndCleansTimedOutRequests() throws Exception {
  String keyA="a".repeat(32),keyB="b".repeat(32);
  var controller=new RelayController(new ObjectMapper(),"{\"a\":\""+keyA+"\",\"b\":\""+keyB+"\"}",1);
  var incoming=new MockHttpServletRequest("GET","/groups/a/api/v1/group/discussions");incoming.addHeader("Authorization","Bearer member-ticket");
  assertEquals(503,assertThrows(ResponseStatusException.class,()->controller.forward("a",incoming)).getStatusCode().value());
  assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.poll("b","Bearer "+keyA)).getStatusCode().value());
  var executor=Executors.newCachedThreadPool();
  try {
   var poll=executor.submit(()->controller.poll("a","Bearer "+keyA));
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
   while(!(Boolean)((java.util.Map<?,?>)controller.status("a").data()).get("online")&&System.nanoTime()<deadline)Thread.sleep(5);
   var forwarded=executor.submit(()->controller.forward("a",incoming));
   var requests=(List<?>)poll.get(2,TimeUnit.SECONDS).data();assertEquals(1,requests.size());var request=(Request)requests.getFirst();
   assertEquals("/api/v1/group/discussions",request.path());assertEquals("Bearer member-ticket",request.headers().get("Authorization"));
   var reply=new Reply(request.id(),401,"application/json","{}".getBytes());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.reply("b","Bearer "+keyB,reply)).getStatusCode().value());
   controller.reply("a","Bearer "+keyA,reply);assertEquals(401,forwarded.get(2,TimeUnit.SECONDS).getStatusCode().value());
   assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.reply("a","Bearer "+keyA,reply)).getStatusCode().value());
   assertEquals(0,((java.util.Map<?,?>)controller.status("a").data()).get("pending"));
   assertEquals(504,assertThrows(ResponseStatusException.class,()->controller.forward("a",incoming)).getStatusCode().value());
   assertEquals(0,((java.util.Map<?,?>)controller.status("a").data()).get("pending"));
   var invalid=new MockHttpServletRequest("GET","/groups/a/api/v1/group/../users");assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.forward("a",invalid)).getStatusCode().value());
   var large=new MockHttpServletRequest("POST","/groups/a/api/v1/group/discussions");large.setContent(new byte[65537]);assertEquals(413,assertThrows(ResponseStatusException.class,()->controller.forward("a",large)).getStatusCode().value());
  }finally{executor.shutdownNow();}
 }
}
