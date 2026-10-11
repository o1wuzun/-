package dev.algotogether.persistence;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
@Configuration
public class PersistenceConfiguration {
 @Bean public JdbcTemplate jdbcTemplate(DataSource source){return new JdbcTemplate(source){
  @Override protected RowMapper<Map<String,Object>> getColumnMapRowMapper(){return new ColumnMapRowMapper(){@Override protected String getColumnKey(String name){return name.toUpperCase(Locale.ROOT);}};}
 };}
}
