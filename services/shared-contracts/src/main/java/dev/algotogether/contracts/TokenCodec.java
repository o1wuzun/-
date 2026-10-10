package dev.algotogether.contracts;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/** Signed, short-lived identity tickets. Passwords never leave the identity service. */
public final class TokenCodec {
    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    public TokenCodec(String publicKey,String privateKey) {
        try {
            var factory=KeyFactory.getInstance("RSA");
            this.publicKey=factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
            this.privateKey=privateKey==null?null:factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
        } catch(Exception e) {throw new IllegalArgumentException("Invalid identity RSA key configuration",e);}
    }
    private byte[] sign(String body) {
        try { var signer=Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey);signer.update(body.getBytes(StandardCharsets.UTF_8));return signer.sign(); }
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    public String issue(String user) {
        String body=user+":"+Instant.now().plusSeconds(3600).getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }
    public String verify(String authorization) {
        try {
            if(authorization==null||!authorization.startsWith("Bearer ")) throw new IllegalArgumentException();
            var parts=authorization.substring(7).split("\\."); if(parts.length!=2)throw new IllegalArgumentException();
            var body=new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8);
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(publicKey);verifier.update(body.getBytes(StandardCharsets.UTF_8));
            if(!verifier.verify(Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            var fields=body.split(":");if(fields.length!=2||Long.parseLong(fields[1])<=Instant.now().getEpochSecond())throw new IllegalArgumentException();
            return fields[0];
        } catch(Exception e) {throw Api.error(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");}
    }
}
