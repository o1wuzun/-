let token=sessionStorage.getItem('java-token')||''
export function setToken(value){token=value;if(value)sessionStorage.setItem('java-token',value);else sessionStorage.removeItem('java-token')}
export async function request(service,path,method='GET',body){
 const response=await fetch(`/${service}/api/v1${path}`,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
 if(response.status===204)return null
 const result=await response.json()
 if(!response.ok)throw new Error(result.error?.message||result.message||'请求失败，请检查服务连接')
 return result.data
}
