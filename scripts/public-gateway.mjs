import http from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../frontend/dist')
const types = { '.html':'text/html; charset=utf-8', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.png':'image/png', '.ico':'image/x-icon', '.woff2':'font/woff2' }
export function createGateway({ identityPort=8081, relayPort=8095, staticRoot=root }={}) {
 return http.createServer(async (req, res) => {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'same-origin')
  const fail=(status,message)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify({error:{message}}))}
  try {
   const url=new URL(req.url,'http://localhost')
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
