package dev.algotogether.contracts;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> business(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("request_id",UUID.randomUUID().toString(),"error",Map.of("code","HTTP_"+e.getStatusCode().value(),"message",e.getReason()==null?"请求失败":e.getReason())));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {return ResponseEntity.badRequest().body(Map.of("error",Map.of("code","VALIDATION_ERROR","message","请检查输入内容")));}
}
