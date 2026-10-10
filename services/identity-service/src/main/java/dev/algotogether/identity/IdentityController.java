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
    private final JdbcTemplate db;
    private final TokenCodec tokens;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public IdentityController(JdbcTemplate db,@Value("${identity.public-key}")String pub,@Value("${identity.private-key}")String priv){this.db=db;tokens=new TokenCodec(pub,priv);}
    public record Registration(@Email @NotBlank String email,@NotBlank @Size(min=8,max=72) String password,@NotBlank @Size(max=40) String nickname){}
    public record Login(@Email @NotBlank String email,@NotBlank String password){}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    Api<?> register(@Valid @RequestBody Registration r){
        String id=UUID.randomUUID().toString();
        try { db.update("INSERT INTO accounts VALUES(?,?,?,?)",id,r.email().toLowerCase(Locale.ROOT),r.nickname(),passwords.encode(r.password())); }
        catch(DuplicateKeyException e){throw Api.error(HttpStatus.CONFLICT,"该邮箱已注册");}
        return Api.of(Map.of("id",id,"nickname",r.nickname()));
    }
    @PostMapping("/auth/login")
    Api<?> login(@Valid @RequestBody Login r){
        var users=db.queryForList("SELECT * FROM accounts WHERE email=?",r.email().toLowerCase(Locale.ROOT));
        if(users.isEmpty()||!passwords.matches(r.password(),(String)users.getFirst().get("PASSWORD_HASH")))throw Api.error(HttpStatus.UNAUTHORIZED,"邮箱或密码错误");
        var u=users.getFirst();return Api.of(Map.of("access_token",tokens.issue((String)u.get("ID")),"expires_in",3600,"user",Map.of("id",u.get("ID"),"nickname",u.get("NICKNAME"))));
    }
    @GetMapping("/users/me") Api<?> me(@RequestHeader(value="Authorization",required=false)String header){
        String id=tokens.verify(header);var users=db.queryForList("SELECT id,nickname,email FROM accounts WHERE id=?",id);
        if(users.isEmpty())throw Api.error(HttpStatus.UNAUTHORIZED,"账号不存在");
        var u=users.getFirst();return Api.of(Map.of("id",id,"nickname",u.get("NICKNAME"),"email",u.get("EMAIL")));
    }
}
