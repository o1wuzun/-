package dev.algotogether.database;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;

/** Offline import and verification. Does not read another project's MySQL configuration. */
public class DatabaseTools {
 static final ObjectMapper JSON=new ObjectMapper();
 static final Map<String,List<String>> TABLES=Map.of("identity",List.of("accounts","user_settings","group_requests","advisor_turns","advisor_generations","public_access_policy","public_access_invites","public_access_sessions","notifications","mail_jobs","deployed_groups"),"group",List.of("members","discussions","comments","event_log","replica_nodes"));
 static String text(JsonNode n,String field){String value=n.path(field).asText();if(value.isBlank())throw new IllegalArgumentException("Missing configuration: "+field);return value;}
 static String identifier(JsonNode n,String field){String value=text(n,field);if(!value.matches("[a-zA-Z0-9_]+"))throw new IllegalArgumentException("Invalid identifier");return value;}
 static String sqlSecret(JsonNode n,String field){String v=text(n,field);if(!v.matches("[a-zA-Z0-9_-]{32,100}"))throw new IllegalArgumentException("Invalid generated password");return v;}
 static String url(JsonNode config,String database){return "jdbc:mysql://127.0.0.1:"+config.path("port").asInt()+"/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8";}
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("Use setup|migrate|verify|backup <config-path> <project-root>");
  Path root=Path.of(args[2]).toAbsolutePath().normalize();var config=JSON.readTree(Files.readString(Path.of(args[1])));
  if(args[0].equals("setup")){setup(config,root);return;}
  if(args[0].equals("check")){check(config);return;}
  if(args[0].equals("shutdown")){try(var c=DriverManager.getConnection(url(config,""),"root",text(config,"admin_password"));var s=c.createStatement()){s.execute("SHUTDOWN");}return;}
  if(args[0].equals("backup")){backup(config,root);return;}
  if(!Set.of("migrate","verify").contains(args[0]))throw new IllegalArgumentException("Unknown operation");
  for(var service:config.path("services")){
   String type=text(service,"type"),db=identifier(service,"database");
   if(!TABLES.containsKey(type))throw new IllegalArgumentException("Invalid service type");
   var flyway=Flyway.configure().dataSource(url(config,db),text(service,"migration_user"),text(service,"migration_password")).locations("filesystem:"+root.resolve("services/"+type+"-service/src/main/resources/db/migration/mysql")).load();flyway.migrate();
   Path source=root.resolve(text(service,"h2_file")).normalize();if(!source.startsWith(root.resolve("data")))throw new IllegalArgumentException("Source must be inside project data directory");
   try(var h2=DriverManager.getConnection("jdbc:h2:file:"+source.toString().replace('\\','/')+";IFEXISTS=TRUE","sa","");var target=DriverManager.getConnection(url(config,db),text(service,"migration_user"),text(service,"migration_password"))){
    if(args[0].equals("migrate"))copy(h2,target,TABLES.get(type));
    for(String table:TABLES.get(type)){var a=fingerprint(h2,table);var b=fingerprint(target,table);if(!a.equals(b))throw new IllegalStateException("Verification mismatch: "+db+"."+table);System.out.println("VERIFIED "+db+"."+table+" rows="+a.get("count"));}
   }
  }
  // A database switch never silently restores old public access.
  if(args[0].equals("migrate"))for(var service:config.path("services"))if(text(service,"type").equals("identity"))try(var c=DriverManager.getConnection(url(config,text(service,"database")),text(service,"migration_user"),text(service,"migration_password"));var s=c.createStatement()){s.executeUpdate("UPDATE public_access_policy SET enabled=FALSE");}
  System.out.println("Import verification complete. Original H2 files are retained. Public access is disabled.");
 }
 static void setup(JsonNode config,Path root)throws Exception{
  String password=sqlSecret(config,"admin_password");Connection connection;
  Path marker=root.resolve("data/mysql/bootstrap.pending");
  try{connection=DriverManager.getConnection(url(config,""),"root",password);}catch(SQLException e){if(!Files.exists(marker))throw e;connection=DriverManager.getConnection(url(config,""),"root","");}
  try(var c=connection;var s=c.createStatement()){
   s.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+password+"'");
   for(var service:config.path("services")){String database=identifier(service,"database"),user=identifier(service,"user"),migrator=identifier(service,"migration_user");s.execute("CREATE DATABASE IF NOT EXISTS `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    for(String kind:List.of("user","migration_user")){String login=identifier(service,kind),pass=sqlSecret(service,kind.equals("user")?"password":"migration_password");s.execute("CREATE USER IF NOT EXISTS '"+login+"'@'127.0.0.1' IDENTIFIED BY '"+pass+"'");}
    s.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON `"+database+"`.* TO '"+user+"'@'127.0.0.1'");s.execute("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON `"+database+"`.* TO '"+migrator+"'@'127.0.0.1'");
   }
  }
  Files.deleteIfExists(marker);
  System.out.println("Project MySQL databases and independent least-privilege application accounts are ready.");
 }
 static void copy(Connection source,Connection target,List<String> tables)throws Exception{
  for(String table:tables)if(!table.equals("public_access_policy"))try(var s=target.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM `"+table+"`")){r.next();if(r.getLong(1)>0)throw new IllegalStateException("Import requires empty target: "+table);}
  target.setAutoCommit(false);
  try{
   if(tables.contains("public_access_policy"))try(var s=target.createStatement()){s.executeUpdate("DELETE FROM public_access_policy");}
   for(String table:tables){if(optionalTableMissing(source,table))continue;try(var s=source.createStatement();var r=s.executeQuery("SELECT * FROM "+table)){
    var md=r.getMetaData();int count=md.getColumnCount();var columns=new ArrayList<String>();for(int i=1;i<=count;i++)columns.add("`"+md.getColumnName(i).toLowerCase(Locale.ROOT)+"`");
    try(var insert=target.prepareStatement("INSERT INTO `"+table+"`("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(count,"?"))+")")){
     int batch=0;while(r.next()){for(int i=1;i<=count;i++){Object value=r.getObject(i);if(value instanceof Clob)insert.setString(i,r.getString(i));else insert.setObject(i,value);}insert.addBatch();if(++batch%200==0)insert.executeBatch();}insert.executeBatch();
    }
   }}
   for(String table:tables)if(!fingerprint(source,table).equals(fingerprint(target,table)))throw new IllegalStateException("Import content mismatch: "+table);
   target.commit();
  }catch(Exception e){target.rollback();throw e;}finally{target.setAutoCommit(true);}
 }
 static Map<String,Object> fingerprint(Connection c,String table)throws Exception{
  if(optionalTableMissing(c,table))return Map.of("count",0L,"digest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest()));
  var keys=new TreeMap<Integer,String>();try(var r=c.getMetaData().getPrimaryKeys(c.getCatalog(),null,c.getMetaData().getDatabaseProductName().equals("H2")?table.toUpperCase(Locale.ROOT):table)){while(r.next())keys.put(r.getInt("KEY_SEQ"),r.getString("COLUMN_NAME"));}
  if(keys.isEmpty())throw new IllegalStateException("Missing primary key: "+table);
  var digest=MessageDigest.getInstance("SHA-256");long count=0;
  try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY "+String.join(",",keys.values()))){int n=r.getMetaData().getColumnCount();while(r.next()){count++;for(int i=1;i<=n;i++){String column=r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT);String value=Set.of("enabled","consumed","revoked","is_read").contains(column)?Boolean.toString(r.getBoolean(i)):r.getString(i);String part=r.wasNull()?"N;":"V"+value.length()+":"+value+";";digest.update(part.getBytes(StandardCharsets.UTF_8));}}}
  return Map.of("count",count,"digest",HexFormat.of().formatHex(digest.digest()));
 }
 // Old offline H2 snapshots legitimately predate the notification schema.
 static boolean optionalTableMissing(Connection c,String table)throws SQLException{
  if(!Set.of("notifications","mail_jobs","deployed_groups").contains(table)||!c.getMetaData().getDatabaseProductName().equals("H2"))return false;
  try(var r=c.getMetaData().getTables(null,null,table.toUpperCase(Locale.ROOT),new String[]{"TABLE"})){return !r.next();}
 }
 static void backup(JsonNode config,Path root)throws Exception{
  Path directory=root.resolve("data/mysql-backups/"+java.time.Instant.now().toString().replace(':','-'));Files.createDirectories(directory);
  for(var service:config.path("services"))try(var c=DriverManager.getConnection(url(config,text(service,"database")),text(service,"migration_user"),text(service,"migration_password"));var s=c.createStatement()){
   c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
   var rows=new LinkedHashMap<String,Object>();for(String table:TABLES.get(text(service,"type")))try(var r=s.executeQuery("SELECT * FROM "+table)){var data=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnName(i),r.getObject(i));data.add(row);}rows.put(table,data);}JSON.writeValue(directory.resolve(text(service,"database")+".json").toFile(),rows);c.commit();
  }
  System.out.println("Database data snapshot written inside ignored data/mysql-backups. Keep backup files private.");
 }
 static void check(JsonNode config)throws Exception{
  for(var service:config.path("services"))try(var c=DriverManager.getConnection(url(config,text(service,"database")),text(service,"user"),text(service,"password"));var s=c.createStatement()){
   try(var r=s.executeQuery("SELECT DATABASE()")){r.next();if(!r.getString(1).equals(text(service,"database")))throw new IllegalStateException("Database mismatch");}
   try{s.execute("CREATE TABLE permission_probe(id INT)");throw new IllegalStateException("Application user unexpectedly has DDL permission");}catch(SQLException e){if(!Set.of(1142,1044).contains(e.getErrorCode()))throw e;}
   for(var other:config.path("services")){if(other.equals(service))continue;String table=TABLES.get(text(other,"type")).getFirst();try{s.executeQuery("SELECT * FROM `"+text(other,"database")+"`."+table);throw new IllegalStateException("Cross-service access unexpectedly allowed");}catch(SQLException e){if(!Set.of(1142,1044).contains(e.getErrorCode()))throw e;}}
   System.out.println("PASS "+text(service,"database")+": application DDL and cross-service database access denied.");
  }
 }
}
