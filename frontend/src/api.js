let token=sessionStorage.getItem('java-token')||''
let groupPath='/groups/group-default'
export function selectGroup(entry){
 if(!entry.proxy_path?.match(/^\/groups\/[a-zA-Z0-9-]+$/))throw new Error('无效的群组服务路径')
 groupPath=entry.proxy_path
}
export function setToken(value){token=value;if(value)sessionStorage.setItem('java-token',value);else sessionStorage.removeItem('java-token')}
export async function request(service,path,method='GET',body){
 const base=service==='group'?groupPath:`/${service}`
 const response=await fetch(`${base}/api/v1${path}`,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status===204)return null
 const result=await response.json()
 if(!response.ok)throw new Error(result.error?.message||result.message||'请求失败，请检查服务连接')
 return result.data
}
