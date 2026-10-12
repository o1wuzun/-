package dev.algotogether.identity;

import dev.algotogether.contracts.Api;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only deployment-controlled service addresses can become upstream targets. */
@Service
public class GroupServiceLocator {
 private final JdbcTemplate db;private final String basic,contest;
 public GroupServiceLocator(JdbcTemplate db,@Value("${advisor.default-group-url:http://127.0.0.1:8082}")String basic,@Value("${advisor.contest-group-url:http://127.0.0.1:8083}")String contest){this.db=db;this.basic=basic;this.contest=contest;}
 public String url(String id){
  if("group-default".equals(id))return basic;if("group-contest".equals(id))return contest;
  var rows=db.queryForList("SELECT port FROM deployed_groups WHERE id=? AND status='READY'",id);
  if(!rows.isEmpty())return "http://127.0.0.1:"+rows.getFirst().get("PORT");
  if(db.queryForObject("SELECT COUNT(*) FROM remote_groups WHERE id=?",Integer.class,id)>0)return "http://127.0.0.1:8095/groups/"+id;
  throw Api.error(HttpStatus.NOT_FOUND,"群组未部署");
 }
}
