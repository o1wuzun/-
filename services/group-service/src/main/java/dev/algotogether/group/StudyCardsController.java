package dev.algotogether.group;

import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Personal question-card packs. Every query is scoped to the configured group and owner. */
@RestController @RequestMapping("/api/v1/group/study/cards")
public class StudyCardsController {
 private final JdbcTemplate db; private final TokenCodec tokens; private final TransactionTemplate tx; private final String group;
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 public StudyCardsController(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager manager,
   @Value("${identity.public-key}")String key,@Value("${group.id}")String group){this.db=db;this.tx=new TransactionTemplate(manager);this.tokens=new TokenCodec(key,null);this.group=group;}
 private String member(String h){String u=tokens.verify(h);if(db.queryForObject("SELECT COUNT(*) FROM members WHERE user_id=? AND revoked=FALSE",Integer.class,u)==0)throw Api.error(HttpStatus.FORBIDDEN,"请先加入当前群组");return u;}
 private static String id(){return UUID.randomUUID().toString();} private static String now(){return Instant.now().toString();}
 private static String today(){return LocalDate.now(ZONE).toString();}
 private static void require(boolean ok,String msg){if(!ok)throw Api.error(HttpStatus.CONFLICT,msg);}
 public record Pack(@NotBlank @Size(max=80)String name,@Size(max=1000)String description){}
 public record Card(@NotBlank @Size(max=120)String title,@NotBlank @Size(max=80)String problem_key,@NotBlank @Size(max=4000)String prompt,@Size(max=4000)String answer_hint,@Pattern(regexp="EASY|MEDIUM|HARD")String difficulty){ }
 @GetMapping Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){String u=member(h);var packs=db.queryForList("SELECT * FROM study_card_packs WHERE group_id=? AND owner_id=? AND active=TRUE ORDER BY created_at DESC",group,u);var cards=db.queryForList("SELECT * FROM study_cards WHERE group_id=? AND owner_id=? ORDER BY created_at DESC",group,u);var draws=db.queryForList("SELECT d.*,c.title,c.problem_key,c.prompt,c.answer_hint,c.difficulty FROM study_card_draws d JOIN study_cards c ON c.group_id=d.group_id AND c.id=d.card_id WHERE d.group_id=? AND d.user_id=? AND d.draw_day=? ORDER BY d.created_at DESC",group,u,today());return Api.of(Map.of("today",today(),"packs",packs,"cards",cards,"draws",draws));}
 @PostMapping("/packs") Api<?> createPack(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Pack p){String u=member(h);String id=id();db.update("INSERT INTO study_card_packs(group_id,id,owner_id,name,description,active,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?)",group,id,u,p.name(),Objects.toString(p.description(),""),now(),now());return Api.of(Map.of("id",id,"name",p.name()));}
 @PostMapping("/packs/{pack}/cards") Api<?> addCard(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String pack,@Valid @RequestBody Card c){String u=member(h);if(db.queryForObject("SELECT COUNT(*) FROM study_card_packs WHERE group_id=? AND id=? AND owner_id=? AND active=TRUE",Integer.class,group,pack,u)==0)throw Api.error(HttpStatus.NOT_FOUND,"卡包不存在或不属于当前用户");String id=id();db.update("INSERT INTO study_cards(group_id,id,pack_id,owner_id,title,problem_key,prompt,answer_hint,difficulty,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",group,id,pack,u,c.title(),c.problem_key(),c.prompt(),Objects.toString(c.answer_hint(),""),Objects.toString(c.difficulty(),"MEDIUM"),now());return Api.of(Map.of("id",id));}
 @PostMapping("/packs/{pack}/draw") Api<?> draw(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String pack){String u=member(h);return tx.execute(t->{var existing=db.queryForList("SELECT d.*,c.title,c.problem_key,c.prompt,c.answer_hint,c.difficulty FROM study_card_draws d JOIN study_cards c ON c.group_id=d.group_id AND c.id=d.card_id WHERE d.group_id=? AND d.pack_id=? AND d.user_id=? AND d.draw_day=?",group,pack,u,today());if(!existing.isEmpty())return Api.of(Map.of("draw",existing.getFirst(),"already_drawn",true));var cards=db.queryForList("SELECT * FROM study_cards WHERE group_id=? AND pack_id=? AND owner_id=?",group,pack,u);if(cards.isEmpty())throw Api.error(HttpStatus.CONFLICT,"卡包还没有题目，请先添加卡牌");var card=cards.get(ThreadLocalRandom.current().nextInt(cards.size()));String draw=id();db.update("INSERT INTO study_card_draws(group_id,id,pack_id,card_id,user_id,draw_day,created_at) VALUES(?,?,?,?,?,?,?)",group,draw,pack,card.get("ID"),u,today(),now());var result=new LinkedHashMap<String,Object>(card);result.put("draw_id",draw);result.put("draw_day",today());return Api.of(Map.of("draw",result,"already_drawn",false));});}
}
