package dev.algotogether.persistence;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import static org.junit.jupiter.api.Assertions.*;
class PortableUpsertTest {
 @Test void duplicateCollisionCannotSilentlySucceedWhenUpdatePredicateRejectsRow(){
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
  try{var db=new JdbcTemplate(source);db.execute("CREATE TABLE protected_rows(id INT PRIMARY KEY, revoked BOOLEAN)");db.update("INSERT INTO protected_rows VALUES(1,TRUE)");
   assertThrows(DuplicateKeyException.class,()->PortableUpsert.execute(db,"UPDATE protected_rows SET revoked=FALSE WHERE id=? AND revoked=FALSE",new Object[]{1},"INSERT INTO protected_rows VALUES(?,FALSE)",new Object[]{1}));
   assertTrue(db.queryForObject("SELECT revoked FROM protected_rows WHERE id=1",Boolean.class));
  }finally{source.shutdown();}
 }
}
