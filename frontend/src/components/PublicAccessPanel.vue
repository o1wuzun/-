<script setup>
import {ref,onMounted} from 'vue'
import {request} from '../api'
const policy=ref(null),busy=ref(false),error=ref('')
async function run(fn){busy.value=true;error.value='';try{await fn()}catch(e){error.value=e.message}finally{busy.value=false}}
async function refresh(){policy.value=await request('identity','/admin/public-access')}
async function toggle(){await run(async()=>{policy.value=await request('identity','/admin/public-access','PUT',{enabled:!policy.value.enabled})})}
onMounted(()=>run(refresh))
</script>
<template>
 <article class="card">
  <div class="section-head"><h3>公网访问权限</h3><button class="secondary" :disabled="busy" @click="run(refresh)">刷新访问状态</button></div>
  <p v-if="policy">{{policy.effective?'公网访问已开放，不再要求额外访问密钥':policy.enabled?'已启用，但公网入口尚未启动':'公网访问已关闭'}}。</p>
  <p class="note">管理员可启用或关闭公网入口。当前不要求公网访问密钥；账号登录、群组成员权限和节点认证继续生效。</p>
  <button v-if="policy" :disabled="busy" @click="toggle">{{policy.enabled?'关闭公网访问':'启用公网访问'}}</button>
  <p class="note">当前为开放公网模式，不再生成或校验额外访问密钥。公网入口开启后，用户仍需登录；私密群组仍要求群组加入密钥。</p>
  <p v-if="error" class="error" role="alert">{{error}}</p>
 </article>
</template>
