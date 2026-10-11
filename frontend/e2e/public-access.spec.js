import {test,expect} from '@playwright/test'

test('public entry needs no invitation while login and shutdown remain enforced',async({browser,request})=>{
 test.skip(!process.env.PUBLIC_TEST_URL||!process.env.TEST_ADMIN_EMAIL||!process.env.TEST_ADMIN_PASSWORD,'Requires running public host and local administrator credentials')
 const base=process.env.PUBLIC_TEST_URL
 const login=await request.post('http://127.0.0.1:8081/api/v1/auth/login',{data:{email:process.env.TEST_ADMIN_EMAIL,password:process.env.TEST_ADMIN_PASSWORD}})
 expect(login.ok()).toBeTruthy()
 const auth={Authorization:'Bearer '+(await login.json()).data.access_token}
 const state=await request.get('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth});const previous=(await state.json()).data.enabled
 const external=await browser.newContext()
 try{
  await request.put('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth,data:{enabled:true}})
  const page=await external.newPage();await page.goto(base)
  await expect(page.locator('.home-panel')).toBeVisible()
  expect((await external.request.get(base+'/identity/api/v1/community/groups')).status()).toBe(200)
  expect((await external.request.get(base+'/identity/api/v1/users/me')).status()).toBe(401)
  expect((await external.request.get(base+'/identity/api/v1/internal/public-access/check')).status()).toBe(404)
  await request.put('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth,data:{enabled:false}})
  expect((await external.request.get(base+'/identity/api/v1/community/groups')).status()).toBe(403)
 }finally{
  await request.put('http://127.0.0.1:8081/api/v1/admin/public-access',{headers:auth,data:{enabled:previous}})
  await external.close()
 }
})
