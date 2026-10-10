package dev.algotogether.contracts;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record Api<T>(String request_id, T data, Map<String,Object> meta) {
    public static <T> Api<T> of(T data) { return new Api<>(UUID.randomUUID().toString(), data, Map.of("has_more",false)); }
    public static ResponseStatusException error(HttpStatus code,String message) { return new ResponseStatusException(code,message); }
}
