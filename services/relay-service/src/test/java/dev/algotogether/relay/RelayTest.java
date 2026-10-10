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
