package dev.algotogether.identity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.algotogether.contracts.Api;
import java.util.List;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Deployment-owned directory: clients cannot register arbitrary proxy targets. */
@RestController @RequestMapping("/api/v1/community")
public class GroupDirectory {
    public record Entry(String id,String name,String description,String proxy_path){}
    private final List<Entry> entries; private final org.springframework.jdbc.core.JdbcTemplate db;
    public GroupDirectory(org.springframework.jdbc.core.JdbcTemplate db,ObjectMapper json,@Value("${directory.groups}")String config) throws Exception {
        this.db=db;entries=List.copyOf(json.readValue(config,new TypeReference<List<Entry>>(){}));
        var ids=new HashSet<String>();
        for(var e:entries) {
            if(e.id()==null||!e.id().matches("[a-zA-Z0-9-]+")||!ids.add(e.id())||!("/groups/"+e.id()).equals(e.proxy_path()))throw new IllegalArgumentException("Invalid group directory configuration");
        }
    }
    @GetMapping("/groups") Api<?> groups(){var result=new java.util.ArrayList<Entry>(entries);for(var row:db.queryForList("SELECT id,name,description FROM deployed_groups WHERE status='READY'"))result.add(new Entry((String)row.get("ID"),(String)row.get("NAME"),(String)row.get("DESCRIPTION"),"/groups/"+row.get("ID")));return Api.of(result);}
}
