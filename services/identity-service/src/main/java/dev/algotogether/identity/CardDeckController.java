package dev.algotogether.identity;

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

/** Global personal card packs. Cards are connected to Codeforces by problem key. */
@RestController @RequestMapping("/api/v1/cards")
public class CardDeckController {
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private final JdbcTemplate db; private final TokenCodec tokens; private final TransactionTemplate tx;
 public CardDeckController(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager manager,@Value("${identity.public-key}")String key){this.db=db;tokens=new TokenCodec(key,null);tx=new TransactionTemplate(manager);}
 private String user(String h){return tokens.verify(h);}
 private static String id(){return UUID.randomUUID().toString();} private static String now(){return Instant.now().toString();} private static String day(){return LocalDate.now(ZONE).toString();}
 private static String s(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase(Locale.ROOT));return Objects.toString(v,"");}
 private static void fail(boolean ok,String msg){if(!ok)throw Api.error(HttpStatus.CONFLICT,msg);}
 public record Pack(@NotBlank @Size(max=80)String name,@Size(max=1000)String description){}
 public record Card(@NotBlank @Size(max=120)String title,@NotBlank @Pattern(regexp="[0-9]{1,7}[A-Z][0-9]?")String problem_key,@NotBlank @Size(max=4000)String prompt,@Size(max=4000)String answer_hint,@Pattern(regexp="EASY|MEDIUM|HARD")String difficulty){}
 public record Suffix(@Size(max=80)String achievement_suffix){}
 @GetMapping Api<?> overview(@RequestHeader(value="Authorization",required=false)String h){String u=user(h);var packs=db.queryForList("SELECT * FROM card_packs WHERE user_id=? ORDER BY created_at DESC",u);var cards=db.queryForList("SELECT * FROM cards WHERE user_id=? ORDER BY created_at DESC",u);var draws=db.queryForList("SELECT d.*,c.title,c.problem_key,c.prompt,c.answer_hint,c.difficulty,c.cf_url FROM card_draws d JOIN cards c ON c.id=d.card_id WHERE d.user_id=? AND d.draw_day=? ORDER BY d.created_at DESC",u,day());var achievements=db.queryForList("SELECT * FROM achievements WHERE user_id=? ORDER BY earned_at DESC",u);int credits=db.queryForObject("SELECT COALESCE((SELECT credits FROM card_credits WHERE user_id=?),0)",Integer.class,u);return Api.of(Map.of("today",day(),"packs",packs,"cards",cards,"draws",draws,"achievements",achievements,"credits",credits));}
 @PostMapping("/packs") Api<?> createPack(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Pack p){String u=user(h),id=id();db.update("INSERT INTO card_packs VALUES(?,?,?,?,?)",id,u,p.name(),Objects.toString(p.description(),""),now());return Api.of(Map.of("id",id,"name",p.name()));}
 @PostMapping("/packs/{pack}/cards") Api<?> addCard(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String pack,@Valid @RequestBody Card c){String u=user(h);if(db.queryForObject("SELECT COUNT(*) FROM card_packs WHERE id=? AND user_id=?",Integer.class,pack,u)==0)throw Api.error(HttpStatus.NOT_FOUND,"卡包不存在");String id=id(),url="https://codeforces.com/problemset/problem/"+c.problem_key().replaceAll("([0-9]+)([A-Z][0-9]?)","$1/$2");db.update("INSERT INTO cards VALUES(?,?,?,?,?,?,?,?,?,?)",id,pack,u,c.title(),c.problem_key(),c.prompt(),Objects.toString(c.answer_hint(),""),Objects.toString(c.difficulty(),"MEDIUM"),url,now());return Api.of(Map.of("id",id,"codeforces_url",url));}
 @PostMapping("/packs/{pack}/draw") Api<?> draw(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String pack){String u=user(h);return tx.execute(t->{var p=db.queryForList("SELECT * FROM card_packs WHERE id=? AND user_id=? FOR UPDATE",pack,u);if(p.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"卡包不存在");var old=db.queryForList("SELECT d.*,c.title,c.problem_key,c.prompt,c.answer_hint,c.difficulty,c.cf_url FROM card_draws d JOIN cards c ON c.id=d.card_id WHERE d.pack_id=? AND d.user_id=? AND d.draw_day=?",pack,u,day());if(!old.isEmpty())return Api.of(Map.of("draw",old.getFirst(),"already_drawn",true));Integer credits=db.queryForObject("SELECT COALESCE((SELECT credits FROM card_credits WHERE user_id=?),0)",Integer.class,u);fail(credits>0,"没有可用抽卡次数，请先完成学习计划或今日学习结算");var cards=db.queryForList("SELECT * FROM cards WHERE pack_id=? AND user_id=?",pack,u);fail(!cards.isEmpty(),"卡包还没有题目");var card=cards.get(ThreadLocalRandom.current().nextInt(cards.size()));String draw=id();db.update("INSERT INTO card_draws VALUES(?,?,?,?,?,?)",draw,pack,s(card,"ID"),u,day(),now());db.update("UPDATE card_credits SET credits=credits-1 WHERE user_id=?",u);return Api.of(Map.of("draw",card,"already_drawn",false,"draw_day",day()));});}
 @PostMapping("/{card}/complete") Api<?> complete(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String card){String u=user(h);var rows=db.queryForList("SELECT * FROM cards WHERE id=? AND user_id=?",card,u);if(rows.isEmpty())throw Api.error(HttpStatus.NOT_FOUND,"卡牌不存在");try{db.update("INSERT INTO card_completions VALUES(?,?,?,?)",id(),card,u,now());db.update("INSERT INTO achievements VALUES(?,?,?,?,?,?)",id(),u,"CARD_"+card,"卡牌完成者","完成 Codeforces 卡牌 "+s(rows.getFirst(),"PROBLEM_KEY"),now());}catch(org.springframework.dao.DuplicateKeyException ignored){}return Api.of(Map.of("completed",true,"achievement_code","CARD_"+card));}
 @PatchMapping("/profile/suffix") Api<?> suffix(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Suffix r){String u=user(h);db.update("UPDATE accounts SET achievement_suffix=? WHERE id=?",Objects.toString(r.achievement_suffix(),""),u);return Api.of(Map.of("achievement_suffix",Objects.toString(r.achievement_suffix(),"")));}
}
