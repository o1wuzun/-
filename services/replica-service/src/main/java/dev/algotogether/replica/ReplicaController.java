package dev.algotogether.replica;
import dev.algotogether.contracts.Api;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/replica")
public class ReplicaController {
    private final ReplicaStore store;private final ReplicaSyncService sync;
    public ReplicaController(ReplicaStore store,ReplicaSyncService sync){this.store=store;this.sync=sync;}
    @GetMapping("/status") Api<?> status(){return Api.of(store.status());}
    @PostMapping("/sync") Api<?> forceSync(){sync.sync();return Api.of(store.status());}
    @GetMapping("/events") Api<?> events(@RequestParam(defaultValue="0") @Min(0)long after){return Api.of(store.events(after));}
}
