import assert from 'node:assert/strict'
import {setTimeout as delay} from 'node:timers/promises'
import {readFile} from 'node:fs/promises'
import {createRequire} from 'node:module'
import {execFile} from 'node:child_process'
import {promisify} from 'node:util'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
const projectRoot=fileURLToPath(new URL('../',import.meta.url))
const settings=JSON.parse(await readFile(new URL('../data/notifications.json',import.meta.url),'utf8'))
assert.equal(settings.environment.SMTP_HOST,'127.0.0.1','Tests require local SMTP configuration')
assert.equal(String(settings.environment.SMTP_PORT),'1025')
const base='http://127.0.0.1:5173/identity/api/v1'
const fixture='http://127.0.0.1:1026'
const info=await (await fetch(fixture+'/')).json()
assert.match(info.service,/本机测试邮箱/,'Only run against the local SMTP capture fixture')
assert(process.env.TEST_ADMIN_EMAIL&&process.env.TEST_ADMIN_PASSWORD,'Set TEST_ADMIN_EMAIL and TEST_ADMIN_PASSWORD')
async function api(path,token='',method='GET',body,expected=200){
 const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})})
 assert.equal(response.status,expected,`${method} ${path}`)
 const json=await response.json();return json.data
}
async function waitFor(fn,timeout=20000){const end=Date.now()+timeout;while(Date.now()<end){const result=await fn();if(result)return result;await delay(500)}throw new Error('Timed out waiting for mail status')}
const admin=(await api('/auth/login','','POST',{email:process.env.TEST_ADMIN_EMAIL,password:process.env.TEST_ADMIN_PASSWORD})).access_token
async function account(){const data={email:`mail-${crypto.randomUUID()}@example.test`,nickname:'邮件测试成员',password:'Local-test-123'};await api('/auth/register','','POST',data,201);return {...data,token:(await api('/auth/login','','POST',data)).access_token}}
const member=await account(),other=await account()
async function decide(status){const request=await api('/community/group-requests',member.token,'POST',{name:`邮件 ${status}`,description:'异步通知场景测试'},201);await api('/admin/group-requests/'+request.id,admin,'PATCH',{status});return (await api('/notifications',member.token)).find(n=>n.REQUEST_ID===request.id)}
async function sent(id){return waitFor(async()=>{const n=(await api('/notifications',member.token)).find(n=>n.ID===id);return n?.MAIL_STATUS==='SENT'&&n})}
for(const status of ['APPROVED','REJECTED']){
 const notice=await decide(status);await sent(notice.ID)
 const messages=await (await fetch(fixture+'/messages')).json()
 assert(messages.some(m=>m.recipient===`<${member.email}>`),'Message reached requester SMTP inbox')
 const latest=messages.filter(m=>m.recipient===`<${member.email}>`).at(-1)
 assert(!/join_key|PRIVATE_KEY|relay_key|password/i.test(latest.raw),'No infrastructure secrets in mail')
 await api(`/notifications/${notice.ID}/read`,other.token,'PATCH',undefined,404)
 await api(`/notifications/${notice.ID}/read`,member.token,'PATCH')
 console.log(`${status}: station notification and SMTP delivery passed`)
}
assert.equal((await api('/notifications',other.token)).length,0)
await api('/admin/mail-jobs',member.token,'GET',undefined,403)
await fetch(fixture+'/failure/on',{method:'POST'})
try{
 const notice=await decide('APPROVED')
 await waitFor(async()=>{const jobs=(await api('/admin/mail-jobs',admin)).jobs;return jobs.find(j=>j.ID===notice.ID&&j.STATUS==='RETRY'&&j.ATTEMPTS===1)})
 console.log('SMTP outage: approval and station notification retained; job awaits retry')
 await fetch(fixture+'/failure/off',{method:'POST'})
 await sentWithLongWait(notice.ID)
 console.log('SMTP recovery: automatic retry delivered successfully')
}finally{await fetch(fixture+'/failure/off',{method:'POST'})}
async function sentWithLongWait(id){return waitFor(async()=>{const jobs=(await api('/admin/mail-jobs',admin)).jobs;return jobs.find(j=>j.ID===id&&j.STATUS==='SENT'&&j.ATTEMPTS===2)},90000)}
console.log('Notification integration passed: real MySQL + RabbitMQ + local SMTP, requester isolation and retry')
const require=createRequire(new URL('../frontend/package.json',import.meta.url))
const amqp=require('amqplib')
const env=settings.environment
const connection=await amqp.connect({hostname:env.RABBITMQ_HOST,port:Number(env.RABBITMQ_PORT),username:env.RABBITMQ_USER,password:env.RABBITMQ_PASSWORD,vhost:env.RABBITMQ_VHOST||'/'})
try{
 const channel=await connection.createConfirmChannel()
 const notice=(await api('/notifications',member.token))[0]
 const before=(await (await fetch(fixture+'/messages')).json()).length
 channel.publish('community.notifications.v1','mail',Buffer.from(notice.ID),{persistent:true,messageId:notice.ID})
 await channel.waitForConfirms();await delay(1500)
 assert.equal((await (await fetch(fixture+'/messages')).json()).length,before,'Duplicate delivery must not resend completed mail')
 await channel.close();console.log('Real RabbitMQ duplicate delivery: completed job was not resent')
}finally{await connection.close()}
if(process.env.RUN_BROKER_FAULT_TESTS==='1'){
 const run=promisify(execFile)
 async function command(file,args=[]){return run(process.env.PWSH_PATH||'pwsh',['-NoProfile','-File',path.join(projectRoot,'scripts',file),...args],{cwd:projectRoot,timeout:60000,windowsHide:true})}
 try{
  await command('stop-messaging.ps1',['-BrokerOnly'])
  const notice=await decide('REJECTED')
  await waitFor(async()=>{const jobs=(await api('/admin/mail-jobs',admin)).jobs;return jobs.find(j=>j.ID===notice.ID&&j.STATUS==='RETRY'&&j.ATTEMPTS===0)},30000)
  console.log('Real broker outage: approval and notification committed, mail retained in outbox')
  await command('start-messaging.ps1')
  await waitFor(async()=>{const n=(await api('/notifications',member.token)).find(n=>n.ID===notice.ID);return n?.MAIL_STATUS==='SENT'},90000)
  console.log('Real broker recovery: retained outbox job delivered automatically')
 }finally{await command('start-messaging.ps1')}
}
