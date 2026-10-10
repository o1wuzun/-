package dev.algotogether.tunnel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunnelWorkerTest {
 @Test void refusesWrongSourceIdentityAndReportsRelayFailureWithoutSecrets()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var sourceId=new AtomicReference<>("group-contest");var polls=new AtomicInteger();
  server.createContext("/api/v1/group",exchange->{byte[] body=("{\"data\":{\"id\":\""+sourceId.get()+"\"}}").getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/api/v1/tunnel/group-default/poll",exchange->{polls.incrementAndGet();exchange.sendResponseHeaders(401,-1);exchange.close();});server.start();
  try{
   String base="http://127.0.0.1:"+server.getAddress().getPort(),secret="a".repeat(32);var json=new ObjectMapper();var worker=new TunnelWorker(json,base,"group-default",secret,base);
   worker.connect();assertEquals("source_identity_mismatch",((Map<?,?>)worker.status().data()).get("state"));assertEquals(0,polls.get());
   sourceId.set("group-default");worker.connect();assertEquals("relay_rejected",((Map<?,?>)worker.status().data()).get("state"));assertEquals(1,polls.get());assertFalse(json.writeValueAsString(worker.status()).contains(secret));
   assertThrows(IllegalArgumentException.class,()->new TunnelWorker(json,"http://public.example.com","group-default",secret,base));
  }finally{server.stop(0);}
 }
}
