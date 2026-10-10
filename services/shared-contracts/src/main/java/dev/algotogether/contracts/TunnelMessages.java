package dev.algotogether.contracts;
import java.util.Map;
public final class TunnelMessages {
 private TunnelMessages(){}
 public record Request(String id,String method,String path,Map<String,String> headers,byte[] body){}
 public record Reply(String id,int status,String contentType,byte[] body){}
}
