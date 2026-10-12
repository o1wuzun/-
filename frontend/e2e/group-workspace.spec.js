import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'
import {randomUUID} from 'node:crypto'

test('independent workspace renders math, routes directed replies and member messages',async({browser})=>{
 const keys=JSON.parse(await readFile(new URL('../../data/dev-keys.json',import.meta.url),'utf8')),contexts=[]
 async function member(name){
  const context=await browser.newContext();contexts.push(context)
  const email=`workspace-${randomUUID()}@example.test`,password='Workspace-test-123'
  expect((await context.request.post('http://127.0.0.1:5173/identity/api/v1/auth/register',{data:{email,password,nickname:name}})).ok()).toBeTruthy()
  const login=await context.request.post('http://127.0.0.1:5173/identity/api/v1/auth/login',{data:{email,password}}),token=(await login.json()).data.access_token
  expect((await context.request.post('http://127.0.0.1:5173/groups/group-default/api/v1/group/join',{headers:{Authorization:`Bearer ${token}`},data:{join_key:keys.join_key,nickname:name,host_name:'workspace-pc'}})).ok()).toBeTruthy()
  const page=await context.newPage();await page.addInitScript(token=>{sessionStorage.setItem('java-token',token);sessionStorage.setItem('selected-group','group-default')},token)
  await page.goto('http://127.0.0.1:5173/#/groups/group-default/home');await expect(page.locator('.group-home')).toBeVisible();return page
 }
 try{
  const suffix=randomUUID().slice(0,8),nameA=`同伴A-${suffix}`,nameB=`同伴B-${suffix}`,a=await member(nameA),b=await member(nameB)
  await expect(a.locator('aside').getByRole('button',{name:'⌂ 首页',exact:true})).toHaveCount(0)
  await expect(a.locator('aside').getByRole('button',{name:'◇ 群组入口',exact:true})).toHaveCount(0)
  await expect(a.locator('aside').getByRole('button',{name:'✓ 每日学习',exact:true})).toBeVisible()
  await a.getByRole('button',{name:'☷ 讨论与复盘',exact:true}).click()
  await a.getByRole('button',{name:'＋ 发布讨论'}).click()
  const title=`公式与定向回复-${suffix}`
  await a.getByLabel('标题',{exact:true}).fill(title);await a.getByLabel('内容',{exact:true}).fill('推导 $x^2+y^2$\n$$\\sum_{i=1}^{n}i=\\frac{n(n+1)}2$$\n<img src=x onerror="window.untrusted=true">')
  await a.locator('.dialog').getByRole('button',{name:'确认',exact:true}).click()
  const pa=a.locator('.post').filter({has:a.getByRole('heading',{name:title,exact:true})})
  await expect(pa.locator('.katex')).toHaveCount(2);expect(await a.evaluate(()=>window.untrusted)).toBeUndefined();await expect(pa.locator('img')).toHaveCount(0)
  await b.getByRole('button',{name:'☷ 讨论与复盘',exact:true}).click();await b.getByRole('button',{name:'同步成员',exact:true}).click()
  const pb=b.locator('.post').filter({has:b.getByRole('heading',{name:title,exact:true})})
  await pb.getByLabel('回复同伴').fill('B 的思路：$O(n \\log n)$');await pb.getByRole('button',{name:'发送回复',exact:true}).click()
  await pa.getByRole('button',{name:'查看 / 刷新回复',exact:true}).click();await pa.getByRole('button',{name:`回复 ${nameB}`,exact:true}).click()
  await expect(pa).toContainText(`正在回复 ${nameB}`);await pa.getByLabel('回复同伴').fill('A 定向给 B：$a^2$');await pa.getByRole('button',{name:'发送回复',exact:true}).click()
  await expect(pa.locator('.comment-item').last()).toContainText(`→ ${nameB}`)
  await b.getByRole('button',{name:'✉ 群组消息',exact:true}).click();await expect(b.locator('.group-messages')).toContainText('A 定向给 B：')
  await a.getByRole('button',{name:'◎ 联网成员',exact:true}).click()
  await a.locator('.member').filter({has:a.getByText(nameB,{exact:true})}).getByRole('button',{name:'发消息',exact:true}).click()
  await a.getByLabel('消息内容').fill('仅给你的消息：$\\sqrt{2}$');await a.locator('.dialog').getByRole('button',{name:'确认',exact:true}).click()
  await b.getByRole('button',{name:'刷新消息',exact:true}).click();await expect(b.locator('.group-messages')).toContainText('仅给你的消息：')
  const dm=b.locator('.inbox-card').filter({hasText:'仅给你的消息：'});await expect(dm.locator('.katex')).toHaveCount(1);await dm.getByRole('button',{name:'标记已读',exact:true}).click();await expect(dm).toContainText('已读')
  await b.getByRole('button',{name:'消息快捷入口',exact:true}).click();await expect(b.getByRole('region',{name:'消息快捷窗口'})).toContainText('仅给你的消息：');await b.getByRole('button',{name:'消息快捷入口',exact:true}).click()
  await b.screenshot({path:test.info().outputPath('group-inbox.png'),fullPage:true})
  await a.getByRole('button',{name:'✦ 机器人顾问',exact:true}).click();await expect(a.locator('.advisor-studio')).toBeVisible();await a.evaluate(()=>scrollTo(0,0));await a.screenshot({path:test.info().outputPath('advisor-workspace.png'),fullPage:true,animations:'disabled'})
  await a.setViewportSize({width:390,height:844});expect(await a.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await a.evaluate(()=>scrollTo(0,0));await a.screenshot({path:test.info().outputPath('advisor-workspace-mobile.png'),fullPage:true,animations:'disabled'})
  await a.getByRole('link',{name:'← 退出群组界面',exact:true}).click();await expect(a.getByRole('button',{name:'◇ 群组入口',exact:true})).toBeVisible();await expect(a.getByRole('button',{name:'✓ 每日学习',exact:true})).toHaveCount(0)
 }finally{for(const context of contexts)await context.close()}
})
