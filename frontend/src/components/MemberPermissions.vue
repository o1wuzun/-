<script setup>
import {ref,onMounted} from 'vue'
import {groupRequest} from '../api'
const props=defineProps({group:Object,user:Object}),emit=defineEmits(['changed'])
const api=groupRequest(props.group),data=ref(null),error=ref(''),busy=ref(false)
async function load(){data.value=await api('/group/study/member-access')}
async function change(m){const reason=window.prompt(m.REVOKED?'填写恢复资格的原因':'填写撤销资格的原因；对方将不能访问本组学习和 Agent');if(!reason)return;busy.value=true;try{await api('/group/study/member-access/'+m.USER_ID,'PUT',{revoked:!m.REVOKED,reason});await load();emit('changed')}catch(e){error.value=e.message}finally{busy.value=false}}
onMounted(()=>load().catch(e=>error.value=e.message))
</script>
<template><section v-if="data?.is_leader" class="card"><h3>群组成员权限</h3><p class="note">撤销后，本组任务、资料和顾问均不可访问。恢复后保留原有学习记录。</p><p v-if="error" class="error">{{error}}</p><div v-for="m in data.members" :key="m.USER_ID" class="section-head"><span>{{m.NICKNAME}} · {{m.REVOKED?'资格已撤销':m.USER_ID===user.id?'组长':'成员'}}</span><button v-if="m.USER_ID!==user.id" class="secondary" :disabled="busy" @click="change(m)">{{m.REVOKED?'恢复资格':'撤销资格'}}</button></div></section></template>
