import http from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../frontend/dist')
const types = { '.html':'text/html; charset=utf-8', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon', '.woff2':'font/woff2' }
const cookieName='__Host-community_access'
const accessPage=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>社区公网访问</title><style>body{font-family:system-ui;background:#f5f7fc;padding:8vh 20px;color:#182c4a}main{max-width:440px;margin:auto;padding:30px;background:white;border-radius:16px}button{box-sizing:border-box;width:100%;padding:12px;margin-top:14px;background:#435de9;color:white;border:0;border-radius:8px}p{line-height:1.7}</style><main><h1>社区公网访问</h1><p>当前公网入口不需要额外访问密钥。进入后仍需登录，并按群组规则加入。</p><button onclick="location.replace('/')">进入社区</button><p id="message" role="alert"></p></main></html>`
export function createGateway({ identityPort=8081, relayPort=8095, staticRoot=root, gatewayKey=process.env.PUBLIC_ACCESS_GATEWAY_KEY||'' }={}) {
 let attempts=0,windowStart=Date.now()
 const internal=async(action,body)=>{
  if(gatewayKey.length<32)throw Error('missing gateway key')
  const response=await fetch(`http://127.0.0.1:${identityPort}/api/v1/internal/public-access/${action}`,{method:'POST',headers:{Authorization:'Bearer '+gatewayKey,'Content-Type':'application/json'},body:JSON.stringify(body),signal:AbortSignal.timeout(3000)})
  return {status:response.status,body:await response.json()}
 }
 return http.createServer(async (req, res) => {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'same-origin')
  const fail=(status,message)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify({error:{message}}))}
  try {
   const url=new URL(req.url,'http://localhost')
   if(url.pathname==='/access'&&req.method==='GET'){
    res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'"});res.end(accessPage);return
   }
   if(url.pathname==='/access/exchange'&&req.method==='POST'){
    if(req.headers.origin&&new URL(req.headers.origin).host!==req.headers.host)return fail(403,'请求来源无效')
    if(Date.now()-windowStart>60000){windowStart=Date.now();attempts=0}
    if(++attempts>30)return fail(429,'尝试过于频繁，请稍后重试')
    let body='';for await(const chunk of req){body+=chunk;if(Buffer.byteLength(body)>4096)return fail(413,'请求内容过大')}
    let data;try{data=JSON.parse(body)}catch{return fail(400,'无效请求')}
    let result;try{result=await internal('exchange',{token:data.token})}catch{return fail(503,'访问验证服务暂不可用')}
    if(result.status!==200)return fail(result.status,result.body.error?.message||'邀请验证失败')
    const session=result.body.data
    res.setHeader('Set-Cookie',`${cookieName}=${session.session}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${session.max_age}`)
    res.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end('{"data":{"accepted":true}}');return
   }
   // These authenticated machine endpoints still verify independent relay credentials downstream.
   const device=/^\/api\/v1\/tunnel\/[a-zA-Z0-9-]+\/(poll|replies)$/.test(url.pathname)
   let session=(req.headers.cookie||'').split(';').map(x=>x.trim()).find(x=>x.startsWith(cookieName+'='))?.slice(cookieName.length+1)
   if(!session)session=req.headers['x-public-access']
   let permit;try{permit=await internal('check',{session:session||'',device})}catch{return fail(503,'访问验证服务暂不可用')}
   if(permit.status!==200)return fail(503,'访问验证服务暂不可用')
   if(!permit.body.data?.allowed){
    res.setHeader('Set-Cookie',`${cookieName}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`)

    return fail(403,'公网访问尚未开启')
   }
   if(url.pathname.startsWith('/identity/api/v1/internal/'))return fail(404,'未找到接口')
   const identity=url.pathname.startsWith('/identity/api/v1/')
   const relay=/^\/(groups\/[a-zA-Z0-9-]+\/api\/v1\/group(?:\/|$)|api\/v1\/(tunnel|relay)\/)/.test(url.pathname)
   if(identity||relay){
    if(!['GET','POST','PUT','PATCH','DELETE'].includes(req.method))return fail(405,'不支持的请求方式')
    if(req.headers['transfer-encoding'])return fail(400,'请使用明确的请求长度')
    const size=Number(req.headers['content-length']||0)
    if(!Number.isSafeInteger(size)||size<0||size>524288)return fail(413,'请求内容过大')
    const headers={}
    for(const key of ['authorization','x-node-credential','content-type','content-length'])if(req.headers[key])headers[key]=req.headers[key]
    const upstream=http.request({host:'127.0.0.1',port:identity?identityPort:relayPort,method:req.method,path:(identity?url.pathname.slice(9):url.pathname)+url.search,headers}, reply=>{
     res.writeHead(reply.statusCode,{'Content-Type':reply.headers['content-type']||'application/json','Cache-Control':'no-store'})
     reply.pipe(res)
     reply.on('error',()=>res.destroy())
    })
    upstream.setTimeout(65000,()=>upstream.destroy(new Error('timeout')))
    upstream.on('error',()=>{if(!res.headersSent)fail(502,'主机服务暂不可用');else res.destroy()})
    req.on('aborted',()=>upstream.destroy())
    req.pipe(upstream)
    return
   }
   if(!['GET','HEAD'].includes(req.method))return fail(405,'不支持的请求方式')
   // Only publish built application assets, never project files or local credentials.
   const pathname=decodeURIComponent(url.pathname)
   if(pathname.includes('\\')||pathname.includes('\0')||pathname.split('/').some(x=>x.startsWith('.')))return fail(404,'未找到页面')
   if(pathname.startsWith('/api/')||pathname.startsWith('/identity/')||pathname.startsWith('/groups/'))return fail(404,'未找到接口')
   let target=path.resolve(staticRoot,'.'+pathname)
   if(target!==staticRoot&&!target.startsWith(staticRoot+path.sep))return fail(404,'未找到页面')
   try{if(!(await stat(target)).isFile())target=path.join(staticRoot,'index.html')}catch{if(path.extname(pathname))return fail(404,'未找到资源');target=path.join(staticRoot,'index.html')}
   const content=await readFile(target)
   res.writeHead(200,{'Content-Type':types[path.extname(target)]||'application/octet-stream','Cache-Control':'no-cache'})
   res.end(req.method==='HEAD'?undefined:content)
  }catch{if(!res.headersSent)fail(400,'无效请求');else res.destroy()}
 })
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 createGateway().listen(Number(process.env.PUBLIC_GATEWAY_PORT||8098),'127.0.0.1',()=>console.log('Public gateway ready on loopback'))
}
