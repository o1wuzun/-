import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{port:5173,proxy:{
 '/identity':{target:'http://127.0.0.1:8081',rewrite:p=>p.replace(/^\/identity/,'')},
 '^/group/':{target:'http://127.0.0.1:8082',rewrite:p=>p.replace(/^\/group/,'')},
 '/groups/group-default':{target:process.env.DEFAULT_GROUP_URL||'http://127.0.0.1:8082',rewrite:p=>p.replace(/^\/groups\/group-default/,'')},
 '/groups/group-contest':{target:process.env.CONTEST_GROUP_URL||'http://127.0.0.1:8083',rewrite:p=>p.replace(/^\/groups\/group-contest/,'')}
}}})
