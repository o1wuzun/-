import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{port:5173,proxy:{'/identity':{target:'http://127.0.0.1:8081',rewrite:p=>p.replace(/^\/identity/,'')},'/group':{target:process.env.GROUP_SERVICE_URL||'http://127.0.0.1:8082',rewrite:p=>p.replace(/^\/group/,'')}}}})
