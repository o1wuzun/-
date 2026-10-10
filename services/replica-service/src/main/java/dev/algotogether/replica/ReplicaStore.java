package dev.algotogether.replica;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class ReplicaStore {
    private final JdbcTemplate db;
    public ReplicaStore(JdbcTemplate db){this.db=db;}
    @Transactional public int append(String groupId,List<Map<String,Object>> events,long next,boolean more){
        int inserted=0;
        for(var e:events){
            String eventId=Objects.toString(e.get("event_id"),"");
            if(db.queryForObject("SELECT COUNT(*) FROM replica_events WHERE event_id=?",Integer.class,eventId)==0){
                db.update("INSERT INTO replica_events(seq,event_id,kind,payload,created_at,received_at) VALUES(?,?,?,?,?,?)",((Number)e.get("seq")).longValue(),eventId,e.get("kind"),e.get("payload"),e.get("created_at"),Instant.now().toString());
                inserted++;
            }
        }
        db.update("UPDATE replica_state SET group_id=?,next_cursor=?,status='synced',last_error=NULL,last_sync_at=?,updated_at=? WHERE id=1",groupId,next,Instant.now().toString(),Instant.now().toString());
        return inserted;
    }
    @Transactional public void failed(String message){db.update("UPDATE replica_state SET status='failed',last_error=?,updated_at=? WHERE id=1",message.length()>1000?message.substring(0,1000):message,Instant.now().toString());}
    public Map<String,Object> status(){var r=db.queryForMap("SELECT * FROM replica_state WHERE id=1");return Map.of("group_id",Objects.toString(r.get("GROUP_ID"),""),"next_cursor",r.get("NEXT_CURSOR"),"status",r.get("STATUS"),"last_error",Objects.toString(r.get("LAST_ERROR"),""),"last_sync_at",Objects.toString(r.get("LAST_SYNC_AT"),""),"event_count",db.queryForObject("SELECT COUNT(*) FROM replica_events",Long.class));}
    public List<Map<String,Object>> events(long after){return db.queryForList("SELECT seq,event_id,kind,payload,created_at,received_at FROM replica_events WHERE seq>? ORDER BY seq LIMIT 100",after);}
}
