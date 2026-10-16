import {defineConfig} from '@playwright/test'
export default defineConfig({
 testDir:'./e2e',workers:1,timeout:60000,
 use:{baseURL:'http://127.0.0.1:5173',channel:'chrome',headless:true,actionTimeout:15000,navigationTimeout:30000,viewport:{width:1366,height:900}},
 reporter:'list'
})
