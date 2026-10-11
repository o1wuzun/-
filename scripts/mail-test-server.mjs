// A local SMTP capture fixture. Never forwards messages to the Internet.
import net from 'node:net'
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
const directory=path.resolve('data/messaging')
fs.mkdirSync(directory,{recursive:true})
let messages=[]
let failing=false
const smtp=net.createServer(socket=>{
 socket.setTimeout(30000,()=>socket.destroy())
 let buffer='',dataMode=false,lines=[],recipient=''
 socket.write('220 localhost capture SMTP\r\n')
 socket.on('data',chunk=>{
  buffer+=chunk.toString();let end
  while((end=buffer.indexOf('\r\n'))>=0){
   const line=buffer.slice(0,end);buffer=buffer.slice(end+2)
   if(dataMode){
    if(line==='.'){
     dataMode=false
     if(failing)socket.write('451 temporary test failure\r\n')
     else{messages.push({recipient,raw:lines.join('\r\n'),received_at:new Date().toISOString()});fs.appendFileSync(path.join(directory,'captured-mails.jsonl'),JSON.stringify(messages.at(-1))+'\n');socket.write('250 captured locally\r\n')}
     lines=[]
    }else lines.push(line.startsWith('..')?line.slice(1):line)
   }else if(/^(EHLO|HELO)/i.test(line))socket.write('250 localhost\r\n')
   else if(/^MAIL FROM:/i.test(line)){recipient='';socket.write('250 OK\r\n')}
   else if(/^RCPT TO:/i.test(line)){recipient=line.slice(8).trim();socket.write('250 OK\r\n')}
   else if(/^DATA$/i.test(line)){dataMode=true;lines=[];socket.write('354 end with dot\r\n')}
   else if(/^QUIT$/i.test(line)){socket.end('221 bye\r\n')}
   else if(/^(RSET|NOOP)/i.test(line))socket.write('250 OK\r\n')
   else socket.write('502 unsupported\r\n')
  }
 })
 socket.on('error',()=>{})
})
smtp.listen(1025,'127.0.0.1')
http.createServer((req,res)=>{
 res.setHeader('Content-Type','application/json; charset=utf-8')
 if(req.method==='GET'&&req.url==='/messages')res.end(JSON.stringify(messages))
 else if(req.method==='GET'&&req.url==='/')res.end(JSON.stringify({service:'本机测试邮箱，仅捕获邮件、不向外投递',messages:messages.length,failing}))
 else if(req.method==='POST'&&req.url==='/failure/on'){failing=true;res.end('{}')}
 else if(req.method==='POST'&&req.url==='/failure/off'){failing=false;res.end('{}')}
 else{res.statusCode=404;res.end('{}')}
}).listen(1026,'127.0.0.1')
console.log('Local mail capture: SMTP 127.0.0.1:1025, HTTP 127.0.0.1:1026')
