<script setup>
import {ref,onMounted} from 'vue'
import {request} from '../api'
const policy=ref(null),busy=ref(false),error=ref(''),name=ref(''),hours=ref(24),token=ref('')
const base=ref(location.protocol==='https:'?location.origin:'')
async function run(fn){busy.value=true;error.value='';try{await fn()}catch(e){error.value=e.message}finally{busy.value=false}}
async function refresh(){policy.value=await request('identity','/admin/public-access')}
async function toggle(){await run(async()=>{policy.value=await request('identity','/admin/public-access','PUT',{enabled:!policy.value.enabled});token.value=''})}
async function create(){await run(async()=>{const data=await request('identity','/admin/public-access/invitations','POST',{name:name.value,hours:Number(hours.value)});token.value=data.token;name.value='';await refresh()})}
async function revoke(id){await run(async()=>{await request('identity','/admin/public-access/invitations/'+id,'DELETE');token.value='';await refresh()})}
function link(){try{const url=new URL(base.value);if(url.protocol!=='https:')return '';return url.origin+'/access#'+token.value}catch{return ''}}
function status(item){return item.REVOKED?'已撤销':item.EXPIRES_AT*1000<=Date.now()?'已过期':item.CONSUMED?'已兑换':'待兑换'}
onMounted(()=>run(refresh))
</script>
<template>
 <article class="card">
  <div class="section-head"><h3>公网访问权限</h3><button class="secondary" :disabled="busy" @click="run(refresh)">刷新访问状态</button></div>
  <p v-if="policy">{{policy.effective?'公网访问已开放，仅有效邀请可进入':policy.enabled?'已启用，但没有有效邀请，入口保持关闭':'公网访问已关闭'}}。</p>
  <p class="note">公网邀请只允许进入系统。进入后仍需登录，私密群组仍需单独邀请。关闭开关会立即使已有访问会话失效；本机管理入口保留。</p>
  <button v-if="policy" :disabled="busy" @click="toggle">{{policy.enabled?'关闭公网访问':'启用公网访问'}}</button>
  <form @submit.prevent="create"><label>邀请备注<input v-model="name" required maxlength="80" placeholder="例如：同学的小组测试设备"></label><label>有效时间（小时，1–168）<input v-model="hours" type="number" min="1" max="168" required></label><button :disabled="busy">生成一次性访问邀请</button></form>
  <div v-if="token"><label>邀请凭证（仅本次显示）<input :value="token" readonly></label><label>当前公网地址<input v-model="base" type="url" placeholder="https://你的公网地址"></label><label v-if="link()">可分享的邀请链接<input :value="link()" readonly></label><p class="note">每个邀请只能兑换一次，访问会话最长 8 小时且不超过邀请有效期。请为不同设备分别生成邀请。</p></div>
  <p v-if="error" class="error" role="alert">{{error}}</p>
  <div v-for="item in policy?.invitations||[]" :key="item.ID" class="member"><div><strong>{{item.NAME}}</strong><small>到期：{{new Date(item.EXPIRES_AT*1000).toLocaleString('zh-CN')}}</small></div><span class="badge">{{status(item)}}</span><button v-if="!item.REVOKED" class="secondary" :disabled="busy" @click="revoke(item.ID)">撤销访问邀请</button></div>
 </article>
</template>
