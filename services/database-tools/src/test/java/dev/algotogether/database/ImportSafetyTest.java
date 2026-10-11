package dev.algotogether.database;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ImportSafetyTest {
 @Test void oldH2SnapshotMayLackNewNotificationTables()throws Exception{
  try(var a=database();var b=database()){
   for(var table:List.of("notifications","mail_jobs"))try(var s=b.createStatement()){s.execute("CREATE TABLE "+table+"(id VARCHAR(36) PRIMARY KEY)");}
   DatabaseTools.copy(a,b,List.of("notifications","mail_jobs"));
   assertEquals(DatabaseTools.fingerprint(a,"notifications"),DatabaseTools.fingerprint(b,"notifications"));
   assertEquals(DatabaseTools.fingerprint(a,"mail_jobs"),DatabaseTools.fingerprint(b,"mail_jobs"));
  }
 }
 Connection database()throws Exception{return DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID(),"sa","");}
 @Test void preservesUnicodeNullsAndRefusesToOverwriteExistingData()throws Exception{
  try(var a=database();var b=database()){
   for(var c:List.of(a,b))try(var s=c.createStatement()){s.execute("CREATE TABLE items(id INT PRIMARY KEY,body CLOB,revoked BOOLEAN)");}
   try(var s=a.prepareStatement("INSERT INTO items VALUES(?,?,?)")){s.setInt(1,1);s.setString(2,"迁移验证🙂".repeat(1000));s.setBoolean(3,false);s.executeUpdate();s.setInt(1,2);s.setNull(2,Types.CLOB);s.setBoolean(3,true);s.executeUpdate();}
   DatabaseTools.copy(a,b,List.of("items"));assertEquals(DatabaseTools.fingerprint(a,"items"),DatabaseTools.fingerprint(b,"items"));
   assertThrows(IllegalStateException.class,()->DatabaseTools.copy(a,b,List.of("items")));assertEquals(2L,DatabaseTools.fingerprint(b,"items").get("count"));
  }
 }
 @Test void failedTableImportRollsBackEarlierTables()throws Exception{
  try(var a=database();var b=database()){
   for(var c:List.of(a,b))try(var s=c.createStatement()){s.execute("CREATE TABLE first_table(id INT PRIMARY KEY)");s.execute("CREATE TABLE second_table(id INT PRIMARY KEY CHECK(id>"+(c==a?"0":"10")+"))");}
   try(var s=a.createStatement()){s.execute("INSERT INTO first_table VALUES(1)");s.execute("INSERT INTO second_table VALUES(1)");}
   assertThrows(SQLException.class,()->DatabaseTools.copy(a,b,List.of("first_table","second_table")));
   assertEquals(0L,DatabaseTools.fingerprint(b,"first_table").get("count"));assertTrue(b.getAutoCommit());assertEquals(1L,DatabaseTools.fingerprint(a,"first_table").get("count"));
  }
 }
}
