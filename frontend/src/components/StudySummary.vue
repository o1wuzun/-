<script setup>
import {ref} from 'vue'
import {request} from '../api'
const props=defineProps({catalog:Array})
const emit=defineEmits(['enter'])
const data=ref(null),error=ref(''),busy=ref(false)
async function load(){busy.value=true;error.value='';try{data.value=await request('identity','/study-overview')}catch(e){error.value=e.message}finally{busy.value=false}}
</script>
<template><section class="card"><div class="section-head"><h3>各群组学习进度</h3><button class="secondary" :disabled="busy" @click="load">{{busy?'正在读取各群组…':'查看 / 刷新'}}</button></div><p class="note">这里只汇总展示，各组的计划、任务和完成状态独立维护。</p><p v-if="error" class="error">{{error}}</p><p v-if="data&&!data.groups.length">暂无可读取的群组进度。先加入一个群组；离线群组暂不展示。</p><article v-for="g in data?.groups||[]" :key="g.group_id"><strong>{{catalog.find(e=>e.id===g.group_id)?.name||g.group_id}}</strong><p>{{g.stage}} · 已完成 {{g.completed}} / {{g.total}}</p><button class="secondary" @click="emit('enter',catalog.find(e=>e.id===g.group_id))">进入该群组</button></article></section></template>
