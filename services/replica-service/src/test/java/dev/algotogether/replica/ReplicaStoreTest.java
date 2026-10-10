package dev.algotogether.replica;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
class ReplicaStoreTest {
 @Test void appendIsIdempotentAndCursorAdvances(){
  var db=new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();var store=new ReplicaStore(new JdbcTemplate(db));
  var e=Map.<String,Object>of("seq",1L,"event_id","evt-1","kind","discussion_created","payload","{}","created_at","2026-01-01T00:00:00Z");
  assertEquals(1,store.append("group-default",List.of(e),1,false));assertEquals(0,store.append("group-default",List.of(e),1,false));
  assertEquals(1L,store.status().get("event_count"));assertEquals(1L,store.status().get("next_cursor"));
 }
}
