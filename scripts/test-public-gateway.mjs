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
 const backend=http.createServer((req,res)=>{let body='';req.on('data',x=>body+=x);req.on('end',()=>{seen.push({path:req.url,authorization:req.headers.authorization,body});res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({data:'ok'}))})})
 await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve))
 const port=backend.address().port
 const gateway=createGateway({identityPort:port,relayPort:port,staticRoot:root})
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
