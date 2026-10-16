package dev.algotogether.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class StudyCardsTest {
 @Test void personalPacksDrawOneStableCardPerDayAndStayScoped() {
  var source=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
  try{
   var db=new JdbcTemplate(source);db.update("INSERT INTO members VALUES('leader','组长','pc',0,FALSE),('student','成员','pc',0,FALSE),('other','其他','pc',0,FALSE)");
   var tokens=new TokenCodec(GroupIntegrationTest.pub,GroupIntegrationTest.priv);String u="Bearer "+tokens.issue("student"),other="Bearer "+tokens.issue("other");
   var c=new StudyCardsController(db,new DataSourceTransactionManager(source),GroupIntegrationTest.pub,"A");
   String pack=(String)((Map<?,?>)c.createPack(u,new StudyCardsController.Pack("二分每日卡包","每天一题")).data()).get("id");
   c.addCard(u,pack,new StudyCardsController.Card("边界","4A","说明左闭右闭边界","先画区间","EASY"));
   c.addCard(u,pack,new StudyCardsController.Card("复杂度","4B","分析复杂度","数循环次数","MEDIUM"));
   var first=(Map<?,?>)((Map<?,?>)c.draw(u,pack).data()).get("draw");var second=(Map<?,?>)((Map<?,?>)c.draw(u,pack).data()).get("draw");
   assertEquals(first.get("PROBLEM_KEY"),second.get("PROBLEM_KEY"));assertEquals(2,((List<?>)((Map<?,?>)c.overview(u).data()).get("cards")).size());assertTrue(((List<?>)((Map<?,?>)c.overview(other).data()).get("packs")).isEmpty());
   assertThrows(ResponseStatusException.class,()->c.draw(other,pack));
  }finally{source.shutdown();}
 }
}
