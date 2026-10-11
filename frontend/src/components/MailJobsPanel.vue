<script setup>
import {ref,onMounted} from 'vue'
import {request} from '../api'
const jobs=ref([]),enabled=ref(false),error=ref(''),busy=ref(false)
const labels={PENDING:'等待发送',PUBLISHING:'进入队列中',QUEUED:'已进入队列',SENDING:'发送中',SENT:'SMTP 已接收',RETRY:'等待重试',FAILED:'发送失败',CONFIG_REQUIRED:'等待邮件配置'}
async function load(){try{const data=await request('identity','/admin/mail-jobs');jobs.value=data.jobs;enabled.value=data.enabled;error.value=''}catch(e){error.value=e.message}}
async function retry(id){busy.value=true;try{await request('identity',`/admin/mail-jobs/${id}/retry`,'POST');await load()}catch(e){error.value=e.message}finally{busy.value=false}}
onMounted(load)
defineExpose({load})
</script>
<template><article class="card"><div class="section-head"><h3>邮件投递</h3><button @click="load">刷新投递状态</button></div><p class="note">{{enabled?'异步邮件已启用。':'异步邮件未启用，邮件任务保留在数据库中。'}} 邮件配置由部署者维护；已提交表示邮件服务器接收，不代表收件人已阅读。</p><p v-if="error" class="error">{{error}}</p><div class="admin-scroll-list" role="region" aria-label="邮件任务滚动列表" tabindex="0"><p v-if="!jobs.length">暂无邮件任务</p><div v-for="j in jobs" :key="j.ID" class="member"><div><strong>{{j.SUBJECT}}</strong><small>{{j.RECIPIENT}} · 尝试 {{j.ATTEMPTS}} 次 · {{labels[j.STATUS]||j.STATUS}}</small><small v-if="j.LAST_ERROR">{{j.LAST_ERROR}}</small></div><button v-if="['FAILED','CONFIG_REQUIRED'].includes(j.STATUS)" :disabled="busy" @click="retry(j.ID)">重新发送</button></div></div></article></template>
