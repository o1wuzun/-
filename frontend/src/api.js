let token=sessionStorage.getItem('java-token')||''
let groupPath='/groups/group-default'
export function selectGroup(entry){
 if(!entry.proxy_path?.match(/^\/groups\/[a-zA-Z0-9-]+$/))throw new Error('无效的群组服务路径')
 groupPath=entry.proxy_path
}
export function setToken(value){token=value;if(value)sessionStorage.setItem('java-token',value);else sessionStorage.removeItem('java-token')}
export async function downloadRemoteBundle(id){
 const response=await fetch(`/identity/api/v1/community/group-requests/${encodeURIComponent(id)}/remote-bundle`,{headers:{Authorization:`Bearer ${token}`}})
 if(!response.ok){const result=await response.json();throw new Error(result.error?.message||result.message||'部署包下载失败')}
 const url=URL.createObjectURL(await response.blob()),link=document.createElement('a')
 link.href=url;link.download=`remote-group-${id}.zip`;link.click();setTimeout(()=>URL.revokeObjectURL(url),10000)
}
export async function request(service,path,method='GET',body){
 const base=service==='group'?groupPath:`/${service}`
 return perform(base,token,path,method,body)
}
// Capture both scope and identity before async work; a group switch cannot retarget a pending write.
export function groupRequest(group){
 const base=group.proxy_path||`/groups/${group.id}`
 if(!/^\/groups\/[a-zA-Z0-9-]+$/.test(base))throw new Error('无效的群组服务路径')
 const identity=token
 return (path,method='GET',body)=>perform(base,identity,path,method,body)
}
async function perform(base,identity,path,method,body){
 const response=await fetch(`${base}/api/v1${path}`,{method,headers:{'Content-Type':'application/json',...(identity?{Authorization:`Bearer ${identity}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status===204)return null
 const result=await response.json()
 if(!response.ok)throw new Error(result.error?.message||result.message||'请求失败，请检查服务连接')
 return result.data
}
