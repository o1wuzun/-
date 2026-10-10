<script setup>
import {ref,onMounted} from 'vue'
import {request} from '../api'
const props=defineProps({joined:Boolean})
const nodes=ref([]),name=ref('我的成员主机'),credential=ref(''),busy=ref(false),error=ref(''),removeId=ref('')
async function load(){nodes.value=await request('group','/group/nodes')}
async function run(fn){error.value='';busy.value=true;try{await fn()}catch(e){error.value=e.message}finally{busy.value=false}}
async function enroll(){await run(async()=>{const data=await request('group','/group/nodes','POST',{name:name.value});credential.value=data.credential;await load()})}
async function remove(){await run(async()=>{await request('group','/group/nodes/'+removeId.value,'DELETE');removeId.value='';await load()})}
onMounted(()=>{if(props.joined)run(load)})
</script>
<template><section><h2>我的同步节点</h2><div v-if="!joined" class="empty">先加入一个群组，再注册成员主机。</div><template v-else><p class="note">节点使用独立凭证读取群组事件；入组密钥不用于持续同步。当前已提供节点认证和增量读取接口，自动复制与离线副本尚未启用。</p><p v-if="error" class="error">{{error}}</p><form class="node-form" @submit.prevent="enroll"><label>设备名称<input v-model="name" maxlength="80" required></label><button :disabled="busy">注册节点</button></form><div v-if="credential" class="card"><h3>保存此节点凭证</h3><p>凭证仅本次创建显示，配置到你自己的成员主机后关闭此提示。</p><code class="credential">{{credential}}</code><button class="secondary" @click="credential=''">已保存，关闭</button></div><div v-for="node in nodes" :key="node.id" class="member"><div><strong>{{node.name}}</strong><small>{{node.id}}</small></div><span class="presence" :class="{offline:node.revoked}">{{node.revoked?'已撤销':'凭证有效'}}</span><button v-if="!node.revoked" class="secondary" :disabled="busy" @click="removeId=node.id">撤销</button></div><div v-if="removeId" class="card"><p>撤销后，此节点不能继续读取群组事件。</p><button :disabled="busy" @click="remove">确认撤销</button> <button class="secondary" @click="removeId=''">取消</button></div></template></section></template>
