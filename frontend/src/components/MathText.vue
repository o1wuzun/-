<script setup>
import {ref,watch,onMounted,nextTick} from 'vue'
import renderMathInElement from 'katex/contrib/auto-render'
import 'katex/dist/katex.min.css'
const props=defineProps({text:{type:String,default:''}}),root=ref(null)
async function render(){await nextTick();if(!root.value)return;root.value.textContent=props.text||'';renderMathInElement(root.value,{delimiters:[{left:'$$',right:'$$',display:true},{left:'\\[',right:'\\]',display:true},{left:'\\(',right:'\\)',display:false},{left:'$',right:'$',display:false}],throwOnError:false,trust:false,strict:'ignore',maxExpand:200,maxSize:20})}
onMounted(render);watch(()=>props.text,render)
</script>
<template><div ref="root" class="math-text"></div></template>
<style scoped>.math-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.85;color:inherit;min-width:0}.math-text :deep(.katex-display){overflow-x:auto;overflow-y:hidden;padding:8px 0;max-width:100%}.math-text :deep(.katex){white-space:normal;font-size:1.08em}</style>
