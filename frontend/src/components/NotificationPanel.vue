<script setup>
import {ref,onMounted,onUnmounted} from 'vue'
import {request} from '../api'
const notices=ref([]),requests=ref([]),error=ref('')
const emit=defineEmits(['changed'])
const labels={PENDING:'等待发送',PUBLISHING:'进入队列中',QUEUED:'已进入队列',SENDING:'发送中',SENT:'邮件已提交',RETRY:'等待重试',FAILED:'发送失败',CONFIG_REQUIRED:'等待邮件配置'}
async function load(){try{const [n,r]=await Promise.all([request('identity','/notifications'),request('identity','/community/group-requests')]);notices.value=n;requests.value=r;error.value=''}catch(e){error.value=e.message}}
async function read(id){try{await request('identity',`/notifications/${id}/read`,'PATCH');await load();emit('changed')}catch(e){error.value=e.message}}
let timer
onMounted(()=>{load();timer=setInterval(()=>{if(!document.hidden)load()},15000)})
onUnmounted(()=>clearInterval(timer))
</script>
<template>
 <section><div class="section-head"><h2>消息与申请</h2><button @click="load">刷新消息</button></div><p class="note">审批结果会保存在这里。邮件发送不影响站内通知；批准后仍需部署群组服务。</p><p v-if="error" class="error">{{error}}</p>
 <p v-if="!notices.length" class="empty">暂无审批通知</p>
 <article v-for="n in notices" :key="n.ID" class="card"><div class="section-head"><h3>{{n.TITLE}}</h3><span class="badge">{{n.IS_READ?'已读':'未读'}}</span></div><p>{{n.BODY}}</p><small>{{new Date(n.CREATED_AT).toLocaleString('zh-CN')}} · 应用内分发：{{n.DELIVERY_STATUS==='DELIVERED'?'已分发':n.DELIVERY_STATUS==='QUEUED'?'队列中':'等待分发'}}</small><button v-if="!n.IS_READ" class="secondary" @click="read(n.ID)">标记已读</button></article>
 <h3>我的群组创建申请</h3><p v-if="!requests.length" class="empty">暂无申请，可在群组入口申请创建。</p><div v-for="r in requests" :key="r.ID" class="member"><div><strong>{{r.NAME}}</strong><small>{{r.DESCRIPTION}}</small></div><span class="badge">{{({PENDING:'待审批',APPROVED:'已批准，待部署',REJECTED:'已驳回'})[r.STATUS]}}</span></div></section>
</template>
