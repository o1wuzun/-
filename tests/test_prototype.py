import os
import tempfile
import unittest
from datetime import date, timedelta

_tmp = tempfile.TemporaryDirectory()
os.environ['COMMUNITY_DB'] = os.path.join(_tmp.name, 'test.db')
from fastapi.testclient import TestClient
from app.main import app

class PrototypeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.c=TestClient(app)
        cls.headers=[]
        for n in range(3):
            data={'email':f'test{n}@example.com','password':'test-password-123','nickname':f'测试{n}'}
            assert cls.c.post('/api/v1/auth/register',json=data).status_code==201
            r=cls.c.post('/api/v1/auth/login',json=data)
            cls.headers.append({'Authorization':'Bearer '+r.json()['data']['access_token']})

    def test_group_isolation_and_presence(self):
        h1,h2,h3=self.headers
        g=self.c.post('/api/v1/community/groups',headers=h1,json={'name':'测试学习群组'}).json()['data']
        root=f"/api/v1/community/groups/{g['id']}"
        self.assertEqual(self.c.get(root+'/members',headers=h2).status_code,404)
        self.assertEqual(self.c.post(root+'/join',headers=h2,json={'join_key':'wrong-key','host_name':'设备'}).status_code,403)
        self.assertEqual(self.c.post(root+'/join',headers=h2,json={'join_key':g['join_key'],'host_name':'设备'}).status_code,201)
        self.assertEqual(self.c.post(root+'/enter',headers=h2,json={}).status_code,200)
        self.assertEqual(len(self.c.get(root+'/members',headers=h1).json()['data']['members']),2)
        post=self.c.post(root+'/discussions',headers=h2,json={'title':'二分边界','body':'如何维护区间不变量？'})
        self.assertEqual(post.status_code,201)
        self.assertEqual(self.c.get(root+'/discussions',headers=h3).status_code,404)
        self.assertEqual(len(self.c.get(root+'/discussions',headers=h1).json()['data']),1)
        self.c.delete(root+'/membership',headers=h2)
        self.assertEqual(self.c.get(root+'/discussions',headers=h2).status_code,404)

    def test_learning_flow(self):
        h=self.headers[0]
        g=self.c.post('/api/v1/goals',headers=h,json={'title':'二分学习','goal_type':'foundation','level':'beginner','target_date':(date.today()+timedelta(days=30)).isoformat(),'daily_minutes':30}).json()['data']
        root=f"/api/v1/goals/{g['id']}"
        self.assertEqual(self.c.post(root+'/transitions',headers=h,json={'action':'activate','version':1}).status_code,200)
        self.assertEqual(self.c.post(root+'/transitions',headers=h,json={'action':'pause','version':1}).status_code,409)
        today=self.c.get(root+'/today',headers=h).json()['data']
        task=today['tasks'][0]['id']
        self.assertEqual(self.c.post(f'/api/v1/tasks/{task}/completions',headers=h,json={}).status_code,200)
        self.assertEqual(self.c.get(root+'/progress',headers=h).json()['data']['task_completion_rate'],1)
        data={'minutes_spent':30,'work_summary':'理解二分边界','task_ids':[task]}
        first=self.c.post(root+'/checkins',headers=h,json=data).json()['data']
        second=self.c.post(root+'/checkins',headers=h,json=data).json()['data']
        self.assertEqual(first['id'],second['id'])
        self.assertEqual(self.c.post(root+'/checkins',headers=h,json={**data,'minutes_spent':40}).status_code,409)
        self.assertEqual(self.c.get(root,headers=self.headers[1]).status_code,404)
        self.assertEqual(self.c.post(root+'/reflections',headers=h,json={'period_type':'daily','period_start':today['local_date'],'period_end':today['local_date'],'core_definition':'单调空间折半'}).status_code,201)

    def test_frontend_served(self):
        self.assertEqual(self.c.get('/').status_code,200)
        self.assertEqual(self.c.get('/static/app.js').status_code,200)
        self.assertIn('算法同行',self.c.get('/').text)

if __name__=='__main__': unittest.main()
