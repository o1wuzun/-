import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { createGateway } from './public-gateway.mjs'

test('gateway publishes built assets and forwards only community APIs',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'community-gateway-'))
 await writeFile(path.join(root,'index.html'),'<h1>Community</h1>')
 const seen=[]
 const backend=http.createServer((req,res)=>{let body='';req.on('data',x=>body+=x);req.on('end',()=>{if(req.url==='/api/v1/internal/public-access/check'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({data:{allowed:true,enabled:true}}));return}seen.push({path:req.url,authorization:req.headers.authorization,body});res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({data:'ok'}))})})
 await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve))
 const port=backend.address().port
 const gateway=createGateway({identityPort:port,relayPort:port,staticRoot:root,gatewayKey:"x".repeat(32)})
 await new Promise(resolve=>gateway.listen(0,'127.0.0.1',resolve))
 const base='http://127.0.0.1:'+gateway.address().port
 try{
  assert.match(await (await fetch(base+'/')).text(),/Community/)
  await fetch(base+'/identity/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"email":"test"}'})
  assert.equal(seen[0].path,'/api/v1/auth/login');assert.match(seen[0].body,/test/)
  await fetch(base+'/groups/group-default/api/v1/group/discussions?after=1',{headers:{Authorization:'Bearer example'}})
  assert.equal(seen[1].path,'/groups/group-default/api/v1/group/discussions?after=1');assert.equal(seen[1].authorization,'Bearer example')
  for(const route of ['/data/dev-keys.json','/.env','/services/identity-service/pom.xml','/identity/private','/api/private','/groups/unknown'])assert.equal((await fetch(base+route)).status,404)
  assert.equal((await fetch(base+'/identity/api/v1/auth/login',{method:'POST',body:'x'.repeat(524289)})).status,413)
  assert.equal(seen.length,2)
 }finally{gateway.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>gateway.close(r)),new Promise(r=>backend.close(r))]);await rm(root,{recursive:true,force:true})}
})

test('perimeter denies pages and APIs, exchanges Secure cookies and fails closed',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'community-access-'))
 await writeFile(path.join(root,'index.html'),'private frontend')
 let enabled=false,used=false,hits=0
 const backend=http.createServer((req,res)=>{let body='';req.on('data',x=>body+=x);req.on('end',()=>{
  res.setHeader('Content-Type','application/json')
  if(req.url.startsWith('/api/v1/internal/public-access/')){
   assert.equal(req.headers.authorization,'Bearer '+'x'.repeat(32))
   const data=JSON.parse(body)
   if(req.url.endsWith('/check'))return res.end(JSON.stringify({data:{enabled,allowed:enabled}}))
   if(!enabled||used||data.token!=='single-use-invite'){res.statusCode=403;return res.end(JSON.stringify({error:{message:'邀请无效'}}))}
   used=true;return res.end(JSON.stringify({data:{session:'valid-session',max_age:3600}}))
  }
  hits++;res.end(JSON.stringify({data:'protected-api'}))
 })})
 await new Promise(r=>backend.listen(0,'127.0.0.1',r));const port=backend.address().port
 const gateway=createGateway({identityPort:port,relayPort:port,staticRoot:root,gatewayKey:'x'.repeat(32)})
 await new Promise(r=>gateway.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+gateway.address().port
 try{
  for(const route of ['/','/assets/test.js','/identity/api/v1/auth/login','/groups/group-default/api/v1/group'])assert.equal((await fetch(base+route)).status,403)
  assert.equal(hits,0)
  assert.match(await(await fetch(base+'/access')).text(),/社区公网访问/)
  enabled=true
  assert.equal((await fetch(base+'/')).status,200)
  const response=await fetch(base+'/access/exchange',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:'single-use-invite'})})
  assert.equal(response.status,200);const cookie=response.headers.get('set-cookie');for(const flag of ['HttpOnly','Secure','SameSite=Strict','Path=/'])assert.ok(cookie.includes(flag));assert.ok(!(await response.text()).includes('valid-session'))
  assert.equal((await fetch(base+'/',{headers:{Cookie:cookie}})).status,200)
  assert.equal((await fetch(base+'/identity/api/v1/internal/public-access/check',{headers:{Cookie:cookie}})).status,404)
  assert.equal((await fetch(base+'/access/exchange',{method:'POST',body:JSON.stringify({token:'single-use-invite'})})).status,403)
  assert.equal((await fetch(base+'/access/exchange',{method:'POST',headers:{Origin:'https://untrusted.example'},body:'{}'})).status,403)
  enabled=false;assert.equal((await fetch(base+'/',{headers:{Cookie:cookie}})).status,403)
  await new Promise(r=>backend.close(r));assert.equal((await fetch(base+'/',{headers:{Cookie:cookie}})).status,503)
 }finally{gateway.closeAllConnections();backend.closeAllConnections();await new Promise(r=>gateway.close(r));await rm(root,{recursive:true,force:true})}
})

test('newly deployed local groups are routed with identity and credential checks',async()=>{
 const root=await mkdtemp(path.join(os.tmpdir(),'community-local-gateway-'))
 const id='local-11111111-1111-1111-1111-111111111111';let correct=true
 const group=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');if(req.url==='/api/v1/group')return res.end(JSON.stringify({data:{id:correct?id:'wrong-group'}}));res.statusCode=req.headers.authorization==='Bearer member'?200:401;res.end(JSON.stringify({data:{path:req.url}}))})
 for(let port=8199;port>=8100;port--){try{await new Promise((resolve,reject)=>{group.once('error',reject);group.listen(port,'127.0.0.1',()=>{group.removeListener('error',reject);resolve()})});break}catch(e){if(e.code!=='EADDRINUSE')throw e}}
 assert.ok(group.listening)
 const configFile=path.join(root,id+'.json');await writeFile(configFile,JSON.stringify({id,port:group.address().port}))
 const identity=http.createServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify({data:{allowed:true,enabled:true}}))})
 await new Promise(r=>identity.listen(0,'127.0.0.1',r))
 const gateway=createGateway({identityPort:identity.address().port,deployedRoot:root,gatewayKey:'x'.repeat(32)})
 await new Promise(r=>gateway.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+gateway.address().port+'/groups/'+id
 try{
  assert.equal((await fetch(base+'/api/v1/group')).status,200)
  assert.equal((await fetch(base+'/api/v1/group/discussions')).status,401)
  const ok=await fetch(base+'/api/v1/group/discussions?after=4',{headers:{Authorization:'Bearer member'}})
  assert.equal(ok.status,200);assert.equal((await ok.json()).data.path,'/api/v1/group/discussions?after=4')
  correct=false;assert.equal((await fetch(base+'/api/v1/group/discussions')).status,503)
  await writeFile(configFile,JSON.stringify({id,port:8081}));assert.equal((await fetch(base+'/api/v1/group')).status,404)
  assert.equal((await fetch(base+'/api/v1/admin/users')).status,404)
 }finally{
  for(const server of [gateway,identity,group]){server.closeAllConnections();await new Promise(r=>server.close(r))}
  await rm(root,{recursive:true,force:true})
 }
})
