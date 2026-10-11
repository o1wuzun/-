<script setup>
import {ref,watch,onMounted,onUnmounted} from 'vue'
import {request} from '../api'
const props=defineProps({user:Object,group:Object,joined:Boolean,unread:Number})
const emit=defineEmits(['page','reply','logout','login','changed'])
const root=ref(null),open=ref(''),notices=ref([]),replies=ref([]),loading=ref(false),error=ref('')
let epoch=0,timer
async function load(){const version=++epoch;loading.value=true;error.value='';try{const results=await Promise.allSettled([request('identity','/notifications'),props.joined?request('group','/group/replies/me'):Promise.resolve([])]);if(version!==epoch)return;notices.value=results[0].status==='fulfilled'?results[0].value.slice(0,5):[];replies.value=results[1].status==='fulfilled'?results[1].value.slice(0,5):[];if(results.some(r=>r.status==='rejected'))error.value='部分消息暂时无法加载，请重试'}finally{if(version===epoch)loading.value=false}}
function toggle(value){open.value=open.value===value?'':value;if(open.value==='messages')load()}
function page(value){open.value='';emit('page',value)}
async function notice(n){try{if(!n.IS_READ)await request('identity',`/notifications/${n.ID}/read`,'PATCH');emit('changed');page('notifications')}catch{error.value='消息暂时无法打开，请重试'}}
function reply(r){open.value='';emit('reply',r.discussion_id)}
function outside(e){if(!root.value?.contains(e.target))open.value=''}
function key(e){if(e.key==='Escape')open.value=''}
watch(()=>[props.user?.id,props.group?.id,props.joined],()=>{epoch++;open.value='';notices.value=[];replies.value=[]})
onMounted(()=>{document.addEventListener('pointerdown',outside);document.addEventListener('keydown',key);timer=setInterval(()=>{if(open.value==='messages'&&!document.hidden)load()},15000)})
onUnmounted(()=>{epoch++;clearInterval(timer);document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',key)})
</script>
<template>
 <div ref="root" class="account-shortcuts">
  <template v-if="user">
   <button class="secondary" aria-label="消息快捷入口" :aria-expanded="open==='messages'" aria-controls="message-popover" @click="toggle('messages')">✉ <span v-if="unread" class="badge">{{unread}}</span></button>
   <button class="account-button" aria-label="用户菜单" :aria-expanded="open==='account'" aria-controls="account-menu" @click="toggle('account')"><img v-if="user.avatar_url" class="avatar mini" :src="user.avatar_url" alt="头像"><span v-else class="avatar mini">{{user.nickname?.slice(0,1)}}</span>{{user.nickname}} ▾</button>
   <section v-if="open==='messages'" id="message-popover" class="shortcut-popover" aria-label="消息快捷窗口"><div class="section-head"><strong>消息快捷栏</strong><button class="secondary" @click="load">刷新</button></div><p v-if="loading" class="muted">加载中…</p><p v-if="error" role="alert">{{error}}</p><h3>系统消息</h3><p v-if="!loading&&!notices.length" class="muted">暂无系统消息</p><button v-for="n in notices" :key="n.ID" class="shortcut-item" @click="notice(n)"><strong>{{n.IS_READ?'':'● '}}{{n.TITLE}}</strong><span>{{n.BODY}}</span></button><h3>讨论回复 · {{group?.name||'尚未连接群组'}}</h3><p v-if="!loading&&!replies.length" class="muted">{{joined?'暂无与你相关的回复':'进入群组后查看讨论回复'}}</p><button v-for="r in replies" :key="r.id" class="shortcut-item" @click="reply(r)"><strong>{{r.nickname}} · {{r.title}}</strong><span>{{r.body}}</span></button><button class="secondary" @click="page('notifications')">查看全部消息与申请 →</button></section>
   <nav v-if="open==='account'" id="account-menu" class="shortcut-popover account-menu" aria-label="账户导航"><button @click="page('settings')">⚙ 个人设置</button><button @click="page('notifications')">消息与申请 <span v-if="unread" class="badge">{{unread}}</span></button><button @click="open='';emit('logout')">退出登录</button></nav>
  </template><template v-else><span>尚未登录</span><button class="secondary" @click="emit('login')">登录 / 注册</button></template>
 </div>
</template>
<style scoped>
.account-shortcuts{position:relative;display:flex;align-items:center;gap:12px}.account-button{display:flex;align-items:center;gap:8px;background:transparent;color:#24344b;padding:8px}.account-button img{object-fit:cover}.shortcut-popover{position:absolute;right:0;top:calc(100% + 12px);z-index:30;background:white;border:1px solid #e4e9f0;border-radius:12px;padding:18px;width:min(380px,calc(100vw - 40px));max-height:70vh;overflow:auto;box-shadow:0 12px 40px #24344b24;text-align:left;color:#24344b}.shortcut-popover h3{font-size:13px;margin:16px 0 8px}.shortcut-item{display:block;width:100%;text-align:left;background:#f5f7fa;color:#24344b;font-weight:normal;margin:8px 0;padding:10px}.shortcut-item strong,.shortcut-item span{display:block;overflow-wrap:anywhere}.shortcut-item span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#63738a;font-size:12px}.account-menu{width:210px}.account-menu button{display:block;width:100%;background:white;color:#24344b;text-align:left}.account-menu button:hover{background:#edf0ff}
</style>
