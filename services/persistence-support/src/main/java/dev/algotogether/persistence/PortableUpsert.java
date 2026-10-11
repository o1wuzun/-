package dev.algotogether.persistence;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
/** SQL is supplied by service code; no shared business tables or cross-service connections. */
public final class PortableUpsert {
 private PortableUpsert(){}
 public static void execute(JdbcTemplate db,String update,Object[] updateArgs,String insert,Object[] insertArgs){
  if(db.update(update,updateArgs)!=0)return;
  try{db.update(insert,insertArgs);}catch(DuplicateKeyException race){if(db.update(update,updateArgs)==0)throw race;}
 }
}
