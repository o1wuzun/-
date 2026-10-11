<script setup>
import {ref,onMounted,onUnmounted} from 'vue'
import {request,downloadRemoteBundle} from '../api'
const notices=ref([]),requests=ref([]),error=ref(''),joinKeys=ref({}),deploying=ref({}),remoteStatus=ref({})
const emit=defineEmits(['changed'])
const labels={PENDING:'等待发送',PUBLISHING:'进入队列中',QUEUED:'已进入队列',SENDING:'发送中',SENT:'邮件已提交',RETRY:'等待重试',FAILED:'发送失败',CONFIG_REQUIRED:'等待邮件配置'}
async function load(){try{const [n,r]=await Promise.all([request('identity','/notifications'),request('identity','/community/group-requests')]);notices.value=n;requests.value=r;error.value='';await Promise.all(r.filter(x=>x.GROUP_ID?.startsWith('remote-')).map(async x=>{try{remoteStatus.value[x.ID]=await request('identity',`/community/group-requests/${x.ID}/remote-status`)}catch(e){remoteStatus.value[x.ID]={online:false,message:e.message}}}))}catch(e){error.value=e.message}}
async function read(id){try{await request('identity',`/notifications/${id}/read`,'PATCH');await load();emit('changed')}catch(e){error.value=e.message}}
async function deploy(id){deploying.value[id]=true;try{await request('identity',`/community/group-requests/${id}/deploy`,'POST');await load()}catch(e){error.value=e.message}finally{deploying.value[id]=false}}
async function remoteDeploy(id){deploying.value[id]=true;try{await request('identity',`/community/group-requests/${id}/remote-deploy`,'POST');await downloadRemoteBundle(id);await load();emit('changed')}catch(e){error.value=e.message;await load();error.value=e.message}finally{deploying.value[id]=false}}
async function download(id){deploying.value[id]=true;try{await downloadRemoteBundle(id)}catch(e){error.value=e.message}finally{deploying.value[id]=false}}
async function copyKey(id){try{await navigator.clipboard.writeText(joinKeys.value[id])}catch{error.value='浏览器不支持自动复制，请选中密钥手动复制。'}}
async function showKey(id){try{joinKeys.value[id]=(await request('identity',`/community/group-requests/${id}/join-key`)).join_key}catch(e){error.value=e.message}}
let timer
onMounted(()=>{load();timer=setInterval(()=>{if(!document.hidden)load()},15000)})
onUnmounted(()=>clearInterval(timer))
</script>
<template>
 <section><div class="section-head"><h2>消息与申请</h2><button @click="load">刷新消息</button></div><p class="note">管理员只负责审批；申请批准后由你点击一键部署。本地部署在运行本系统的主机进行；如需使用另一台电脑，请下载远程部署包，解压后运行 start.cmd（需要 Java 21 或以上）。远程电脑主动连接公网中继，无需配置路由器；私密群组完成部署后，你可以在本页查看并复制加入密钥，再分享给成员。</p><p v-if="error" class="error">{{error}}</p>
 <p v-if="!notices.length" class="empty">暂无审批通知</p>
 <article v-for="n in notices" :key="n.ID" class="card"><div class="section-head"><h3>{{n.TITLE}}</h3><span class="badge">{{n.IS_READ?'已读':'未读'}}</span></div><p>{{n.BODY}}</p><small>{{new Date(n.CREATED_AT).toLocaleString('zh-CN')}} · 应用内分发：{{n.DELIVERY_STATUS==='DELIVERED'?'已分发':n.DELIVERY_STATUS==='QUEUED'?'队列中':'等待分发'}}</small><button v-if="!n.IS_READ" class="secondary" @click="read(n.ID)">标记已读</button></article>
 <h3>我的群组创建申请</h3><p v-if="!requests.length" class="empty">暂无申请，可在群组入口申请创建。</p><div v-for="r in requests" :key="r.ID" class="member"><div><strong>{{r.NAME}}</strong><small>{{r.VISIBILITY==='PRIVATE'?'私密群组':'公开群组'}} · {{r.DESCRIPTION}}</small><small v-if="r.DEPLOYMENT_ERROR">{{r.DEPLOYMENT_ERROR}}</small><small>部署状态：{{({NOT_DEPLOYED:'尚未部署',DEPLOYING:'部署中',DEPLOYED:'已部署',REMOTE_PENDING:'已注册中继，等待远程主机',FAILED:'部署失败，可重试'})[r.DEPLOYMENT_STATUS]}}</small></div><span class="badge">{{({PENDING:'待审批',APPROVED:'已批准',REJECTED:'已驳回'})[r.STATUS]}}</span><button v-if="r.STATUS==='APPROVED'&&['NOT_DEPLOYED','FAILED'].includes(r.DEPLOYMENT_STATUS)"  :disabled="deploying[r.ID]" @click="deploy(r.ID)">一键部署本地群组</button><button v-if="r.STATUS==='APPROVED'&&['NOT_DEPLOYED','FAILED'].includes(r.DEPLOYMENT_STATUS)" :disabled="deploying[r.ID]" @click="remoteDeploy(r.ID)">部署到其他电脑</button><template v-if="r.GROUP_ID?.startsWith('remote-')"><small>{{remoteStatus[r.ID]?.online?'远程主机在线':remoteStatus[r.ID]?.message||'正在检查连接'}}</small><button :disabled="deploying[r.ID]" @click="download(r.ID)">重新下载部署包</button><a :href="`#/groups/${r.GROUP_ID}/home`">打开群组</a></template><button v-if="r.VISIBILITY==='PRIVATE'&&['DEPLOYED','REMOTE_PENDING'].includes(r.DEPLOYMENT_STATUS)" @click="showKey(r.ID)">查看加入密钥</button><template v-if="joinKeys[r.ID]"><code class="credential">{{joinKeys[r.ID]}}</code><button class="secondary" @click="copyKey(r.ID)">复制加入密钥</button></template></div></section>
</template>
