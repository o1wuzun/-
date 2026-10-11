import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue(),{name:'local-group-proxy',configureServer(server){server.middlewares.use((req,res,next)=>{if(!req.url.startsWith('/groups/local-'))return next();const match=req.url.match(/^\/groups\/(local-[a-f0-9-]{36})(\/.*)$/);if(!match){res.statusCode=404;return res.end()}try{const config=JSON.parse(fs.readFileSync(path.resolve('../data/deployed-groups',match[1]+'.json'),'utf8'));if(config.id!==match[1]||config.port<8100||config.port>8199)throw new Error();const upstream=http.request({hostname:'127.0.0.1',port:config.port,path:match[2],method:req.method,headers:req.headers},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)});upstream.on('error',()=>{res.statusCode=503;res.end(JSON.stringify({message:'群组服务尚未就绪'}))});req.pipe(upstream)}catch{res.statusCode=404;res.end()}})}}],server:{port:5173,proxy:{
 '/groups/remote-':{target:'http://127.0.0.1:8095'},
 '/identity':{target:'http://127.0.0.1:8081',rewrite:p=>p.replace(/^\/identity/,'')},
 '^/group/':{target:'http://127.0.0.1:8082',rewrite:p=>p.replace(/^\/group/,'')},
 '/groups/group-default':{target:process.env.DEFAULT_GROUP_URL||'http://127.0.0.1:8082',rewrite:p=>p.replace(/^\/groups\/group-default/,'')},
 '/groups/group-contest':{target:process.env.CONTEST_GROUP_URL||'http://127.0.0.1:8083',rewrite:p=>p.replace(/^\/groups\/group-contest/,'')}
}}})
