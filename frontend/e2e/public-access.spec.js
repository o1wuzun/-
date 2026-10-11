import {test,expect} from '@playwright/test'

test('administrator invitation, public entry and immediate revocation',async({browser,request})=>{
 test.skip(!process.env.PUBLIC_TEST_URL||!process.env.TEST_ADMIN_EMAIL||!process.env.TEST_ADMIN_PASSWORD,'Requires running public host and local administrator credentials')
 const base=process.env.PUBLIC_TEST_URL
 const login=await request.post('http://127.0.0.1:8081/api/v1/auth/login',{data:{email:process.env.TEST_ADMIN_EMAIL,password:process.env.TEST_ADMIN_PASSWORD}})
 expect(login.ok()).toBeTruthy()
 const auth={Authorization:'Bearer '+(await login.json()).data.access_token}
 await request.put('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth,data:{enabled:false}})
 const local=await browser.newContext(),external=await browser.newContext()
 let invitationId
 try{
  const page=await local.newPage()
  await page.goto('http://127.0.0.1:5173')
  await page.getByRole('button',{name:'登录 / 注册',exact:true}).click()
  await page.getByLabel('邮箱',{exact:true}).fill(process.env.TEST_ADMIN_EMAIL)
  await page.getByLabel('密码',{exact:true}).fill(process.env.TEST_ADMIN_PASSWORD)
  await page.getByRole('button',{name:'确认',exact:true}).click()
  await page.getByRole('button',{name:'管理后台',exact:true}).click()
  await expect(page.getByText('公网访问已关闭。',{exact:true})).toBeVisible()
  const publicPage=await external.newPage()
  await publicPage.goto(base)
  await expect(publicPage.getByRole('heading',{name:'社区访问邀请'})).toBeVisible()
  expect((await external.request.get(base+'/identity/api/v1/community/groups')).status()).toBe(403)
  await page.getByRole('button',{name:'启用公网访问',exact:true}).click()
  await page.getByLabel('邀请备注').fill('浏览器公网权限验收')
  const created=page.waitForResponse(r=>r.url().endsWith('/public-access/invitations')&&r.request().method()==='POST')
  await page.getByRole('button',{name:'生成一次性访问邀请',exact:true}).click()
  invitationId=(await(await created).json()).data.id
  const token=await page.getByLabel('邀请凭证（仅本次显示）').inputValue()
  await publicPage.goto(base+'/access#'+token)
  await publicPage.getByRole('button',{name:'验证并进入',exact:true}).click()
  await expect(publicPage.getByRole('heading',{name:'找到你的算法同伴'})).toBeVisible()
  const cookies=await external.cookies();const cookie=cookies.find(x=>x.name==='__Host-community_access')
  expect(cookie.secure).toBe(true);expect(cookie.httpOnly).toBe(true)
  await publicPage.screenshot({path:'test-results/public-access-authorized.png',fullPage:true})
  await page.getByText('浏览器公网权限验收',{exact:true}).locator('..').locator('..').getByRole('button',{name:'撤销访问邀请'}).click()
  await expect(page.getByText('浏览器公网权限验收',{exact:true}).locator('..').locator('..').getByText('已撤销')).toBeVisible()
  expect((await external.request.get(base+'/identity/api/v1/community/groups')).status()).toBe(403)
  await publicPage.reload()
  await expect(publicPage.getByRole('heading',{name:'社区访问邀请'})).toBeVisible()
  await page.getByRole('button',{name:'关闭公网访问',exact:true}).click()
  await expect(page.getByText('公网访问已关闭。',{exact:true})).toBeVisible()
 }finally{
  if(invitationId)await request.delete('http://127.0.0.1:8081/api/v1/admin/public-access/invitations/'+invitationId,{headers:auth})
  await request.put('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth,data:{enabled:false}})
  await local.close();await external.close()
 }
})
