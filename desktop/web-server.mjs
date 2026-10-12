import http from 'node:http'
import {readFile,stat} from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
const project=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const root=path.join(project,'frontend/dist')
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.woff2':'font/woff2','.woff':'font/woff','.ttf':'font/ttf'}
http.createServer(async(req,res)=>{
 const fail=(code)=>{res.writeHead(code,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{message:'服务暂不可用'}}))}
 try{
  const u=new URL(req.url,'http://localhost');let port,forward=req.url
  if(u.pathname.startsWith('/identity/')){port=8081;forward=req.url.slice(9)}
  else if(u.pathname.startsWith('/group/')){port=8082;forward=req.url.slice(6)}
  else if(u.pathname.startsWith('/groups/remote-'))port=8095
  else{
   const m=u.pathname.match(/^\/groups\/(group-default|group-contest|local-[a-f0-9-]{36})(\/.*)$/)
   if(m){forward=m[2]+u.search;port=m[1]==='group-default'?8082:m[1]==='group-contest'?8083:JSON.parse(await readFile(path.join(project,'data/deployed-groups',m[1]+'.json'),'utf8')).port;if(port<8082||port>8199)throw Error()}
  }
  if(port){const proxy=http.request({hostname:'127.0.0.1',port,path:forward,method:req.method,headers:{...req.headers,host:`127.0.0.1:${port}`}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)});proxy.on('error',()=>fail(503));req.on('aborted',()=>proxy.destroy());req.pipe(proxy);return}
  const file=path.resolve(root,'.'+decodeURIComponent(u.pathname==='/'?'/index.html':u.pathname));if(!file.startsWith(root+path.sep))return fail(403)
  if(!(await stat(file)).isFile())return fail(404)
  res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','X-Content-Type-Options':'nosniff'});res.end(await readFile(file))
 }catch{fail(404)}
}).listen(5173,process.argv[process.argv.indexOf('--host')+1]||'127.0.0.1')
