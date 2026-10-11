<script setup>
import {ref,onMounted} from 'vue'
import {request} from '../api'
const props=defineProps({group:Object})
const own=ref(null),key=ref(''),error=ref(''),copied=ref(false)
onMounted(async()=>{try{own.value=(await request('identity','/community/group-requests')).find(r=>r.GROUP_ID===props.group?.id)}catch(e){error.value=e.message}})
async function show(){try{key.value=(await request('identity',`/community/group-requests/${own.value.ID}/join-key`)).join_key}catch(e){error.value=e.message}}
async function copy(){try{await navigator.clipboard.writeText(key.value);copied.value=true}catch{error.value='浏览器不支持自动复制，请选中下方密钥手动复制。'}}
</script>
<template><article v-if="own&&group?.visibility==='PRIVATE'" class="card"><h3>群主邀请管理</h3><p>你是本群组创建人。加入密钥由部署时自动生成，可由你分享给成员，无需管理员分发。</p><button @click="show">查看加入密钥</button><template v-if="key"><label>群组加入密钥<input :value="key" readonly></label><button class="secondary" @click="copy">{{copied?'已复制':'复制加入密钥'}}</button></template><p v-if="error" class="error">{{error}}</p></article></template>
