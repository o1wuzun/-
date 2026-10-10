package dev.algotogether.group.replication;
import dev.algotogether.contracts.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/group")
public class ReplicationController {
    private final NodeRegistry registry;
    public ReplicationController(NodeRegistry registry){this.registry=registry;}
    public record Node(@NotBlank @Size(max=80)String name){}
    @PostMapping("/nodes") @ResponseStatus(HttpStatus.CREATED)
    Api<?> enroll(@RequestHeader(value="Authorization",required=false)String h,@Valid @RequestBody Node node){return Api.of(registry.enroll(h,node.name()));}
    @GetMapping("/nodes") Api<?> list(@RequestHeader(value="Authorization",required=false)String h){return Api.of(registry.list(h));}
    @DeleteMapping("/nodes/{node}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@RequestHeader(value="Authorization",required=false)String h,@PathVariable String node){registry.revoke(h,node);}
    @GetMapping("/replication/events") Api<?> read(@RequestHeader(value="X-Node-Credential",required=false)String credential,@RequestParam(defaultValue="0")long after){if(after<0)throw Api.error(HttpStatus.BAD_REQUEST,"游标不能为负数");return Api.of(registry.read(credential,after));}
}
