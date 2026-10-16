<script setup>
import {ref,onMounted} from 'vue'
import {groupRequest} from '../api'
import MathText from './MathText.vue'
const props=defineProps({group:Object})
const api=groupRequest(props.group),items=ref([]),shared=ref([]),error=ref(''),busy=ref(false)
const isLeader=ref(false)
const form=ref({title:'',kind:'ARTICLE',body:''})
async function load(){isLeader.value=(await api('/group/study/member-access')).is_leader;[items.value,shared.value]=await Promise.all([api('/group/study/resources'),api('/group/study/shared-reviews')])}
async function run(fn){busy.value=true;error.value='';try{await fn();await load()}catch(e){error.value=e.message}finally{busy.value=false}}
onMounted(()=>run(load))
</script>
<template><section><div class="section-head"><h2>组内资料</h2><button class="secondary" :disabled="busy" @click="run(load)">刷新</button></div><p class="note">{{group.name}} 的知识点、文章、题单与沉淀内容。私人复习仅在本人主动分享后展示。</p><p v-if="error" class="error" role="alert">{{error}}</p><article v-for="r in items" :key="r.ID" class="card"><span class="badge">{{({ARTICLE:'文章',KNOWLEDGE:'知识点',PROBLEMS:'题单',NOTICE:'公告'})[r.KIND]}}</span><h3>{{r.TITLE}}</h3><MathText :text="r.BODY"/></article><h3>同伴主动分享的错题</h3><article v-for="r in shared" :key="r.ID" class="card"><h4>{{r.TITLE}} · {{r.NICKNAME}}</h4><p>{{r.PROBLEM_KEY}}</p><MathText :text="r.REASON"/></article><form class="card" @submit.prevent="run(async()=>{await api('/group/study/resources','POST',form);form={title:'',kind:'ARTICLE',body:''}})"><h3>分享资料</h3><label>类型<select v-model="form.kind"><option value="ARTICLE">文章</option><option value="KNOWLEDGE">知识点</option><option value="PROBLEMS">题单</option><option v-if="isLeader" value="NOTICE">组内公告</option></select></label><label>标题<input v-model="form.title" required maxlength="120"></label><label>正文 / 链接<textarea v-model="form.body" required maxlength="20000"></textarea></label><button :disabled="busy">发布到当前群组</button></form></section></template>
