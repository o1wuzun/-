import {test,expect} from '@playwright/test'
import {randomUUID} from 'node:crypto'

test('approved owner downloads remote bundle and sees connection status',async({page,request})=>{
 const id=randomUUID(),email=`remote-ui-${randomUUID()}@example.test`
 const response=await request.post('/identity/api/v1/auth/register',{data:{email,nickname:'远程部署测试',password:'Browser-test-123'}})
 expect(response.ok()).toBeTruthy()
 const login=await request.post('/identity/api/v1/auth/login',{data:{email,password:'Browser-test-123'}})
 const token=(await login.json()).data.access_token
 await page.addInitScript(token=>sessionStorage.setItem('java-token',token),token)
 let prepared=false,online=false
 await page.route('**/identity/api/v1/community/groups',route=>route.fulfill({json:{data:prepared?[{id:`remote-${id}`,name:'远程群组入口测试',description:'独立主机',proxy_path:`/groups/remote-${id}`}]:[]}}))
 await page.route(`**/groups/remote-${id}/api/v1/group`,route=>route.fulfill({json:{data:{id:`remote-${id}`,name:'远程群组入口测试',description:'独立主机',visibility:'PRIVATE'}}}))
 await page.route(`**/groups/remote-${id}/api/v1/group/heartbeat`,route=>route.fulfill({status:404,json:{error:{message:'尚未加入群组'}}}))
 const base=`/identity/api/v1/community/group-requests`
 await page.route(`**${base}`,route=>route.fulfill({json:{data:[{ID:id,NAME:'远程群组入口测试',DESCRIPTION:'独立主机',VISIBILITY:'PRIVATE',STATUS:'APPROVED',DEPLOYMENT_STATUS:online?'DEPLOYED':prepared?'REMOTE_PENDING':'NOT_DEPLOYED',GROUP_ID:prepared?`remote-${id}`:null}]}}))
 await page.route(`**${base}/${id}/remote-deploy`,route=>{expect(route.request().method()).toBe('POST');prepared=true;return route.fulfill({json:{data:{group_id:`remote-${id}`}}})})
 await page.route(`**${base}/${id}/remote-bundle`,route=>{expect(route.request().headers().authorization).toBe(`Bearer ${token}`);return route.fulfill({contentType:'application/octet-stream',headers:{'Content-Disposition':'attachment; filename=remote.zip'},body:Buffer.from('bundle-fixture')})})
 await page.route(`**${base}/${id}/remote-status`,route=>route.fulfill({json:{data:{online,message:online?'远程主机已连接':'等待目标电脑启动'}}}))
 await page.goto('/')
 await page.getByRole('button',{name:'用户菜单',exact:true}).click()
 await page.getByRole('button',{name:'消息与申请',exact:true}).click()
 const row=page.locator('.member').filter({hasText:'远程群组入口测试'})
 await expect(row.getByRole('button',{name:'部署到其他电脑',exact:true})).toBeVisible()
 const downloadPromise=page.waitForEvent('download')
 await row.getByRole('button',{name:'部署到其他电脑',exact:true}).click()
 expect((await downloadPromise).suggestedFilename()).toBe(`remote-group-${id}.zip`)
 await expect(row).toContainText('等待目标电脑启动')
 await expect(row.getByRole('button',{name:'一键部署本地群组',exact:true})).toHaveCount(0)
 await expect(row.getByRole('button',{name:'重新下载部署包',exact:true})).toBeVisible()
 await expect(row.getByRole('link',{name:'打开群组',exact:true})).toHaveAttribute('href',`#/groups/remote-${id}/home`)
 online=true;await page.getByRole('button',{name:'刷新消息',exact:true}).click()
 await expect(row).toContainText('远程主机在线')
 await row.getByRole('link',{name:'打开群组',exact:true}).click()
 await expect(page.getByRole('dialog')).toBeVisible()
 await expect(page.getByLabel('群主分享的联网密钥',{exact:true})).toBeVisible()
})
