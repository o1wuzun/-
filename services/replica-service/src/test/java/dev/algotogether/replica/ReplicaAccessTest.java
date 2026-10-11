package dev.algotogether.replica;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class ReplicaAccessTest {
 @Test void independentPerimeterAndNodeCredentialsAndNoCursorAdvanceWhenRevoked(){
  var db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("classpath:schema.sql").build();
  try{var store=new ReplicaStore(new JdbcTemplate(db));var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
   var sync=new ReplicaSyncService(store,builder,"https://community.example/groups/group-default","node-secret","access-session");
   server.expect(requestTo("https://community.example/groups/group-default/api/v1/group/replication/events?after=0")).andExpect(header("X-Node-Credential","node-secret")).andExpect(header("X-Public-Access","access-session")).andRespond(withStatus(HttpStatus.FORBIDDEN));
   sync.sync();assertEquals(0L,store.status().get("next_cursor"));server.verify();
  }finally{db.shutdown();}
 }
}
