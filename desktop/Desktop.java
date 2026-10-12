import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.*;
import java.net.URI;
import java.util.*;

/** Native control panel. All child services use a fresh per-user workspace. */
public class Desktop {
 static Path app,work;static JTextArea log;static JFrame frame;static java.util.List<JButton> controls=new ArrayList<>();static boolean busy=false;static Properties settings=new Properties();static FileChannel lockChannel;static FileLock lock;
 public static void main(String[] args)throws Exception{
  app=Path.of(Desktop.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
  work=Path.of(System.getenv("LOCALAPPDATA"),"AlgoTogetherDemo","v1");Files.createDirectories(work);
  lockChannel=FileChannel.open(work.resolve("desktop.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=lockChannel.tryLock();
  if(lock==null){JOptionPane.showMessageDialog(null,"桌面 Demo 已经运行，请使用已有窗口。");return;}
  Path props=work.resolve("desktop.properties");if(Files.exists(props))try(var in=Files.newInputStream(props)){settings.load(in);}
  UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
  SwingUtilities.invokeAndWait(()->{
   frame=new JFrame("算法同行 · PC Demo 0.1");frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);frame.setSize(850,600);frame.setLocationRelativeTo(null);
   JPanel panel=new JPanel(new BorderLayout(15,15));panel.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));frame.setContentPane(panel);
   JLabel heading=new JLabel("算法同行  /  桌面演示版");heading.setFont(new Font("Microsoft YaHei UI",Font.BOLD,24));panel.add(heading,BorderLayout.NORTH);
   log=new JTextArea("选择启动模式。首次启动会创建独立的本地 MySQL 数据库。\n社区页面在默认浏览器中打开。\n数据目录："+work+"\n");log.setEditable(false);log.setFont(new Font("Microsoft YaHei UI",Font.PLAIN,14));log.setLineWrap(true);log.setWrapStyleWord(true);panel.add(new JScrollPane(log),BorderLayout.CENTER);
   JPanel buttons=new JPanel(new GridLayout(2,3,10,10));panel.add(buttons,BorderLayout.SOUTH);
   button(buttons,"本机启动",()->launch("local",false));button(buttons,"局域网启动",()->launch("lan",false));button(buttons,"公网启动",()->launch("public",false));
   button(buttons,"打开社区",()->open("http://127.0.0.1:5173/"));button(buttons,"打开数据 / 日志",()->{try{java.awt.Desktop.getDesktop().open(work.toFile());}catch(Exception e){append(e.toString());}});button(buttons,"停止服务",()->launch("stop",false));
   frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(!busy)launch("stop",true);}});frame.setVisible(true);
  });
 }
 static void button(JPanel p,String title,Runnable action){JButton b=new JButton(title);b.setFont(new Font("Microsoft YaHei UI",Font.PLAIN,15));b.addActionListener(e->action.run());p.add(b);controls.add(b);}
 static void append(String s){SwingUtilities.invokeLater(()->{log.append(s+"\n");log.setCaretPosition(log.getDocument().getLength());});}
 static void open(String url){try{java.awt.Desktop.getDesktop().browse(URI.create(url));}catch(Exception e){append(e.toString());}}
 static void launch(String mode,boolean exit){
  if(busy)return;
  if(!mode.equals("stop")&&!settings.containsKey("admin")){
   String email=JOptionPane.showInputDialog(frame,"输入首次管理员邮箱，然后在社区注册这个邮箱。\n密码由你在注册页面设置。","管理员配置",JOptionPane.PLAIN_MESSAGE);
   if(email==null)return;if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")){JOptionPane.showMessageDialog(frame,"请输入有效邮箱。");return;}settings.setProperty("admin",email);
   try(var out=Files.newOutputStream(work.resolve("desktop.properties"))){settings.store(out,"Desktop demo settings");}catch(Exception e){append(e.toString());return;}
  }
  busy=true;controls.forEach(b->b.setEnabled(false));append("正在处理："+mode+"，请稍候…");
  new Thread(()->{boolean ok=false;try{
   // Only immutable application files are copied; user data is never bundled or overwritten.
   try(var files=Files.walk(app.resolve("payload"))){for(Path source:files.toList()){Path target=work.resolve(app.resolve("payload").relativize(source));if(Files.isDirectory(source))Files.createDirectories(target);else if(!Files.exists(target))Files.copy(source,target);}}
   Path tools=app.resolve("tools");Files.createDirectories(work.resolve("data/tools"));if(!Files.exists(work.resolve("data/tools/cloudflared.exe")))Files.copy(tools.resolve("cloudflared.exe"),work.resolve("data/tools/cloudflared.exe"));
   try(var files=Files.walk(tools.resolve("mysql"))){for(Path source:files.toList()){Path target=work.resolve("tools/mysql").resolve(tools.resolve("mysql").relativize(source));if(Files.isDirectory(source))Files.createDirectories(target);else if(!Files.exists(target))Files.copy(source,target);}}
   ProcessBuilder pb=new ProcessBuilder(tools.resolve("pwsh/pwsh.exe").toString(),"-NoProfile","-ExecutionPolicy","Bypass","-File",work.resolve("desktop/run.ps1").toString(),"-Mode",mode,"-AdminEmail",settings.getProperty("admin",""));
   pb.directory(work.toFile());pb.redirectErrorStream(true);
   pb.environment().put("PATH",Path.of(System.getProperty("java.home"),"bin")+";"+tools.resolve("node")+";"+tools.resolve("pwsh")+";"+work.resolve("tools/mysql/bin")+";"+pb.environment().get("PATH"));pb.environment().put("JAVA_HOME",System.getProperty("java.home"));
   Process process=pb.start();try(var reader=process.inputReader()){String line;while((line=reader.readLine())!=null)append(line);}ok=process.waitFor()==0;
   append(ok?"操作完成。":"操作失败，请查看上方信息和数据目录中的日志。");
   if(ok&&!mode.equals("stop")){append("管理员邮箱："+settings.getProperty("admin")+"（首次使用请注册）");open("http://127.0.0.1:5173/");}
  }catch(Exception e){append(e.toString());}finally{boolean finished=ok;SwingUtilities.invokeLater(()->{busy=false;controls.forEach(b->b.setEnabled(true));if(exit&&finished)System.exit(0);});}},"community-launch").start();
 }
}
