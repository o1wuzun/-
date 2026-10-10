package dev.algotogether.identity;
import dev.algotogether.contracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final JdbcTemplate db; private final String adminEmails;
    private final TokenCodec tokens;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public IdentityController(JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${identity.private-key}")String priv,@Value("${identity.admin-emails:}")String adminEmails){this.db=db;this.adminEmails=adminEmails;tokens=new TokenCodec(pub,priv);}
    public record Registration(@Email @NotBlank String email,@NotBlank @Size(min=8,max=72) String password,@NotBlank @Size(max=40) String nickname){}
    public record Login(@Email @NotBlank String email,@NotBlank String password){}
    public record Profile(@Size(max=40) String nickname,@Size(max=500) String avatar_url){}
    public record Password(@NotBlank String current_password,@NotBlank @Size(min=8,max=72) String new_password){}
    public record Integrations(@Size(max=40) String oj_provider,@Size(max=500) String oj_api_key,@Size(max=40) String advisor_provider,@Size(max=500) String advisor_api_key){}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    Api<?> register(@Valid @RequestBody Registration r){
        String id=UUID.randomUUID().toString();
        try { db.update("INSERT INTO accounts(id,email,nickname,password_hash) VALUES(?,?,?,?)",id,r.email().toLowerCase(Locale.ROOT),r.nickname(),passwords.encode(r.password())); }
        catch(DuplicateKeyException e){throw Api.error(HttpStatus.CONFLICT,"该邮箱已注册");}
        if(Arrays.stream(adminEmails.split(",")).map(String::trim).anyMatch(x->x.equalsIgnoreCase(r.email())))db.update("UPDATE accounts SET role='ADMIN' WHERE id=?",id);
        return Api.of(Map.of("id",id,"nickname",r.nickname()));
    }
    @PostMapping("/auth/login")
    Api<?> login(@Valid @RequestBody Login r){
        var users=db.queryForList("SELECT * FROM accounts WHERE email=?",r.email().toLowerCase(Locale.ROOT));
        if(users.isEmpty()||!passwords.matches(r.password(),(String)users.getFirst().get("PASSWORD_HASH")))throw Api.error(HttpStatus.UNAUTHORIZED,"邮箱或密码错误");
        var u=users.getFirst();return Api.of(Map.of("access_token",tokens.issue((String)u.get("ID")),"expires_in",3600,"user",profile((String)u.get("ID"))));
    }
    @GetMapping("/users/me") Api<?> me(@RequestHeader(value="Authorization",required=false)String header){
        String id=tokens.verify(header);var users=db.queryForList("SELECT id,nickname,email,avatar_url FROM accounts WHERE id=?",id);
        if(users.isEmpty())throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");
        return Api.of(profile(id));
    }
    private Map<String,Object> profile(String id){var u=db.queryForMap("SELECT id,nickname,email,avatar_url,role FROM accounts WHERE id=?",id);var s=db.queryForList("SELECT oj_provider,oj_api_key,advisor_provider,advisor_api_key FROM user_settings WHERE user_id=?",id);var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("nickname",u.get("NICKNAME"));out.put("email",u.get("EMAIL"));out.put("avatar_url",u.get("AVATAR_URL"));out.put("role",u.get("ROLE"));var integrations=new LinkedHashMap<String,Object>();if(!s.isEmpty()){var row=s.getFirst();integrations.put("oj_provider",row.get("OJ_PROVIDER"));integrations.put("oj_key_configured",row.get("OJ_API_KEY")!=null&&!row.get("OJ_API_KEY").toString().isBlank());integrations.put("advisor_provider",row.get("ADVISOR_PROVIDER"));integrations.put("advisor_key_configured",row.get("ADVISOR_API_KEY")!=null&&!row.get("ADVISOR_API_KEY").toString().isBlank());}out.put("integrations",integrations);return out;}
    @PatchMapping("/users/me") Api<?> updateProfile(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Profile r){String id=tokens.verify(h);db.update("UPDATE accounts SET nickname=COALESCE(?,nickname),avatar_url=COALESCE(?,avatar_url) WHERE id=?",r.nickname(),r.avatar_url(),id);return Api.of(profile(id));}
    @PostMapping("/users/me/password") Api<?> updatePassword(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Password r){String id=tokens.verify(h);var hash=db.queryForObject("SELECT password_hash FROM accounts WHERE id=?",String.class,id);if(!passwords.matches(r.current_password(),hash))throw Api.error(HttpStatus.BAD_REQUEST,"当前密码不正确");db.update("UPDATE accounts SET password_hash=? WHERE id=?",passwords.encode(r.new_password()),id);return Api.of(Map.of("updated",true));}
    @PutMapping("/users/me/integrations") Api<?> updateIntegrations(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Integrations r){String id=tokens.verify(h);db.update("MERGE INTO user_settings(user_id,oj_provider,oj_api_key,advisor_provider,advisor_api_key) KEY(user_id) VALUES(?,?,?,?,?)",id,r.oj_provider(),r.oj_api_key(),r.advisor_provider(),r.advisor_api_key());return Api.of(profile(id));}
}
