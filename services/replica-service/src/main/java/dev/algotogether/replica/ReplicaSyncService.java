package dev.algotogether.replica;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
@Service
public class ReplicaSyncService {
    public record ApiEnvelope(String request_id,Payload data,Map<String,Object> meta){}
    public record Payload(String group_id,List<Map<String,Object>> events,long next_cursor,boolean has_more){}
    private final ReplicaStore store;private final RestClient client;private final String credential;
    public ReplicaSyncService(ReplicaStore store,RestClient.Builder builder,@Value("${replica.source-url}")String source,@Value("${replica.node-credential}")String credential){this.store=store;this.credential=credential;client=builder.baseUrl(source).build();}
    @Scheduled(fixedDelayString="${replica.poll-ms:5000}") public void scheduled(){if(credential==null||credential.isBlank())return;sync();}
    public synchronized void sync(){
        try {
            long cursor=((Number)store.status().get("next_cursor")).longValue();
            var result=client.get().uri(uri->uri.path("/api/v1/group/replication/events").queryParam("after",cursor).build()).header("X-Node-Credential",credential).retrieve().body(ApiEnvelope.class);
            if(result==null||result.data()==null)throw new IllegalStateException("群组服务返回空同步结果");
            store.append(result.data().group_id(),result.data().events(),result.data().next_cursor(),result.data().has_more());
        } catch(Exception e){store.failed(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    }
}
