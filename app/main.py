from __future__ import annotations

import hashlib, hmac, json, os, secrets, sqlite3, time
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response, status
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse

DB_PATH = os.getenv("COMMUNITY_DB", os.path.join(os.path.dirname(__file__), "..", "community.db"))
SECRET = os.getenv("COMMUNITY_SECRET", "development-secret-change-me").encode()

def now() -> str: return datetime.now(timezone.utc).isoformat()
def local_date(tz):
    try: return datetime.now(ZoneInfo(tz)).date().isoformat()
    except ZoneInfoNotFoundError: raise HTTPException(400,'无效时区')
def uid(prefix: str) -> str: return f"{prefix}_{secrets.token_hex(10)}"
def db() -> sqlite3.Connection:
    c = sqlite3.connect(DB_PATH); c.row_factory = sqlite3.Row; c.execute("PRAGMA foreign_keys=ON"); return c
def init_db():
    c=db(); c.executescript('''
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,nickname TEXT NOT NULL,timezone TEXT NOT NULL DEFAULT 'Asia/Shanghai',role TEXT NOT NULL DEFAULT 'learner',version INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,expires_at INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS goals(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,title TEXT NOT NULL,goal_type TEXT NOT NULL,level TEXT NOT NULL,path_template_id TEXT,target_date TEXT,daily_minutes INTEGER NOT NULL,timezone TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'draft',version INTEGER NOT NULL DEFAULT 1,plan_version INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,goal_id TEXT NOT NULL,local_date TEXT NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,estimated_minutes INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'pending',evidence_level TEXT,completed_at TEXT,UNIQUE(goal_id,local_date,title));
    CREATE TABLE IF NOT EXISTS checkins(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,goal_id TEXT NOT NULL,local_date TEXT NOT NULL,minutes_spent INTEGER NOT NULL,work_summary TEXT NOT NULL,task_ids TEXT,reflection_id TEXT,created_at TEXT NOT NULL,UNIQUE(user_id,goal_id,local_date));
    CREATE TABLE IF NOT EXISTS reflections(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,goal_id TEXT NOT NULL,period_type TEXT NOT NULL,period_start TEXT NOT NULL,period_end TEXT NOT NULL,content TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(user_id,goal_id,period_type,period_start));
    CREATE TABLE IF NOT EXISTS knowledge_points(id TEXT PRIMARY KEY,title TEXT NOT NULL,definition TEXT NOT NULL,topic TEXT,level TEXT,version INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS checkpoints(id TEXT PRIMARY KEY,knowledge_point_id TEXT,title TEXT NOT NULL,required INTEGER NOT NULL DEFAULT 1,version INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS submissions(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,checkpoint_id TEXT NOT NULL,attempt_id TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'queued',score REAL,created_at TEXT NOT NULL,UNIQUE(user_id,checkpoint_id,attempt_id));
    CREATE TABLE IF NOT EXISTS groups_(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL,goal_type TEXT,level TEXT,capacity INTEGER NOT NULL,join_policy TEXT NOT NULL,visibility TEXT NOT NULL,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS memberships(id TEXT PRIMARY KEY,group_id TEXT NOT NULL,user_id TEXT NOT NULL,status TEXT NOT NULL,role TEXT NOT NULL DEFAULT 'member',created_at TEXT NOT NULL,UNIQUE(group_id,user_id));
    CREATE TABLE IF NOT EXISTS posts(id TEXT PRIMARY KEY,group_id TEXT NOT NULL,author_id TEXT NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'published',version INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS comments(id TEXT PRIMARY KEY,post_id TEXT NOT NULL,author_id TEXT NOT NULL,body TEXT NOT NULL,parent_id TEXT,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS notifications(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,type TEXT NOT NULL,payload TEXT NOT NULL,read INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS idempotency(user_id TEXT NOT NULL,key TEXT NOT NULL,request_hash TEXT NOT NULL,response TEXT NOT NULL,PRIMARY KEY(user_id,key));
    CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,type TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,sent INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS local_groups(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',join_key_hash TEXT NOT NULL,service_url TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS local_group_members(id TEXT PRIMARY KEY,group_id TEXT NOT NULL,user_id TEXT NOT NULL,host_name TEXT NOT NULL,host_address TEXT,role TEXT NOT NULL DEFAULT 'member',status TEXT NOT NULL DEFAULT 'offline',last_seen_at TEXT NOT NULL,UNIQUE(group_id,user_id));
    CREATE INDEX IF NOT EXISTS idx_local_members_group ON local_group_members(group_id,status,last_seen_at);
    ''')
    if not c.execute('SELECT 1 FROM knowledge_points LIMIT 1').fetchone():
        c.execute('INSERT INTO knowledge_points VALUES(?,?,?,?,?,?)',('kp_binary_search','二分查找','在有序或具备单调性的搜索空间中折半缩小范围','algorithms','beginner',1))
        c.execute('INSERT INTO checkpoints VALUES(?,?,?,?,?)',('cp_binary_search','kp_binary_search','二分边界与不变量说明',1,1))
    c.commit(); c.close()
init_db()

def hash_password(p:str)->str: return hashlib.pbkdf2_hmac('sha256',p.encode(),SECRET,120_000).hex()
def token(user_id:str)->str:
    raw=f"{user_id}.{int(time.time())+86400}.{secrets.token_urlsafe(18)}"; sig=hmac.new(SECRET,raw.encode(),hashlib.sha256).hexdigest(); return raw+'.'+sig
def current_user(authorization: Optional[str]=Header(default=None)):
    if not authorization or not authorization.startswith('Bearer '): raise HTTPException(401,'未授权')
    t=authorization[7:]; parts=t.split('.');
    if len(parts)!=4 or not hmac.compare_digest(parts[3],hmac.new(SECRET,'.'.join(parts[:3]).encode(),hashlib.sha256).hexdigest()): raise HTTPException(401,'令牌无效')
    if int(parts[1]) < int(time.time()): raise HTTPException(401,'令牌已过期')
    c=db(); row=c.execute('SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked=0',(hashlib.sha256(t.encode()).hexdigest(),)).fetchone(); c.close()
    if not row: raise HTTPException(401,'会话无效')
    return dict(row)

def ok(data: Any, request_id: str='') -> dict: return {'request_id':request_id or uid('req'),'data':data,'meta':{'next_cursor':None,'has_more':False}}
def rowdict(r): return dict(r) if r else None
def must_owner(c, table, ident, user_id):
    r=c.execute(f'SELECT * FROM {table} WHERE id=? AND user_id=?',(ident,user_id)).fetchone()
    if not r:
        c.close()
        raise HTTPException(404,'资源不存在或不可见')
    return r

class Register(BaseModel): email: str; password: str=Field(min_length=8); nickname: str=Field(min_length=1,max_length=40)
class Login(BaseModel): email: str; password: str; group_id: Optional[str]=None
class UserPatch(BaseModel): nickname: Optional[str]=None; timezone: Optional[str]=None; version: int
class GoalCreate(BaseModel): title:str; goal_type:str; level:str; path_template_id:Optional[str]=None; target_date:str; daily_minutes:int=Field(ge=10,le=480); timezone:str='Asia/Shanghai'
class GoalPatch(BaseModel): title:Optional[str]=None; target_date:Optional[str]=None; daily_minutes:Optional[int]=Field(default=None,ge=10,le=480); version:int
class Transition(BaseModel): action:str; version:int
class Completion(BaseModel): evidence_ids:list[str]=[]; notes:str=''
class Checkin(BaseModel): minutes_spent:int=Field(ge=1,le=1440); work_summary:str; task_ids:list[str]=[]; reflection_id:Optional[str]=None
class Reflection(BaseModel): period_type:str; period_start:str; period_end:str; knowledge_point_ids:list[str]=[]; core_definition:str; applicable_conditions:Optional[str]=None; extension_methods:Optional[str]=None; complexity_analysis:Optional[str]=None; examples:Optional[str]=None; mistakes:Optional[str]=None; questions:Optional[str]=None; next_step:Optional[str]=None
class ReflectionPatch(BaseModel): content:dict[str,Any]; version:int
class GroupCreate(BaseModel): title:str; description:str=''; goal_type:Optional[str]=None; level:Optional[str]=None; capacity:int=Field(ge=2,le=50); join_policy:str='open'; visibility:str='public'
class PostCreate(BaseModel): group_id:str; type:str; title:str; body:str; knowledge_point_ids:list[str]=[]; visibility:str='group'
class PostPatch(BaseModel): title:Optional[str]=None; body:Optional[str]=None; version:int
class CommentCreate(BaseModel): body:str; parent_id:Optional[str]=None
class Submission(BaseModel): attempt_id:str; checkpoint_version:int; answers:list[dict[str,Any]]=[]; code:Optional[str]=None; language:Optional[str]=None; explanation:dict[str,Any]={}; evidence_ids:list[str]=[]
class LocalGroupCreate(BaseModel): name:str=Field(min_length=1,max_length=80); description:str=''; service_url:Optional[str]=None
class LocalGroupJoin(BaseModel): join_key:str=Field(min_length=8); host_name:str=Field(min_length=1,max_length=80); host_address:Optional[str]=None
class LocalGroupHeartbeat(BaseModel): host_name:Optional[str]=None; host_address:Optional[str]=None

app=FastAPI(title='目标驱动的算法学习社区 API',version='1.0.0')
app.add_middleware(CORSMiddleware,allow_origins=['*'],allow_methods=['*'],allow_headers=['*'])

@app.exception_handler(HTTPException)
async def api_error(request:Request, exc:HTTPException):
    codes={400:'VALIDATION_ERROR',401:'UNAUTHORIZED',403:'FORBIDDEN',404:'RESOURCE_NOT_FOUND',409:'INVALID_STATE'}
    return JSONResponse(status_code=exc.status_code,content={'request_id':uid('req'),'error':{'code':codes.get(exc.status_code,'REQUEST_ERROR'),'message':exc.detail}})

@app.get('/')
def homepage(): return FileResponse(os.path.join(os.path.dirname(__file__),'static','index.html'))

@app.get('/health')
def health(): return {'status':'ok','time':now()}
@app.post('/api/v1/auth/register',status_code=201)
def register(body:Register,request:Request):
    c=db();
    try:
        u=uid('usr'); c.execute('INSERT INTO users VALUES(?,?,?,?,?,?,?,?)',(u,body.email,hash_password(body.password),body.nickname,'Asia/Shanghai','learner',1,now())); c.commit()
    except sqlite3.IntegrityError: c.close(); raise HTTPException(409,'邮箱已注册')
    c.close(); return ok({'id':u,'email':body.email,'nickname':body.nickname,'timezone':'Asia/Shanghai','version':1},request.headers.get('X-Request-ID',''))
@app.post('/api/v1/auth/login')
def login(body:Login):
    c=db(); r=c.execute('SELECT * FROM users WHERE email=?',(body.email,)).fetchone()
    if not r or not hmac.compare_digest(r['password_hash'],hash_password(body.password)): c.close(); raise HTTPException(401,'邮箱或密码错误')
    t=token(r['id']); c.execute('INSERT INTO sessions VALUES(?,?,?,0)', (hashlib.sha256(t.encode()).hexdigest(),r['id'],int(time.time())+86400))
    selected_group=None
    if body.group_id:
        selected_group=c.execute('SELECT lg.id,lg.name,lg.service_url,lgm.role FROM local_groups lg JOIN local_group_members lgm ON lgm.group_id=lg.id WHERE lg.id=? AND lgm.user_id=?',(body.group_id,r['id'])).fetchone()
        if not selected_group: c.close(); raise HTTPException(403,'你不是该局部联网群组成员')
        c.execute('UPDATE local_group_members SET status="online",last_seen_at=? WHERE group_id=? AND user_id=?',(now(),body.group_id,r['id']))
    c.commit(); c.close(); return ok({'access_token':t,'token_type':'bearer','expires_in':86400,'user':{'id':r['id'],'email':r['email'],'nickname':r['nickname']},'selected_group':dict(selected_group) if selected_group else None})
@app.post('/api/v1/auth/logout',status_code=204)
def logout(authorization:Optional[str]=Header(default=None), user=Depends(current_user)):
    if authorization:
        c=db(); c.execute('UPDATE sessions SET revoked=1 WHERE token_hash=?',(hashlib.sha256(authorization[7:].encode()).hexdigest(),)); c.execute('UPDATE local_group_members SET status="offline" WHERE user_id=?',(user['id'],)); c.commit(); c.close()
    return Response(status_code=204)
@app.get('/api/v1/users/me')
def me(user=Depends(current_user)): return ok({k:user[k] for k in ('id','email','nickname','timezone','role','version')})
@app.patch('/api/v1/users/me')
def patch_me(body:UserPatch,user=Depends(current_user)):
    if body.version!=user['version']: raise HTTPException(409,'记录已更新，请重新读取')
    c=db(); c.execute('UPDATE users SET nickname=COALESCE(?,nickname),timezone=COALESCE(?,timezone),version=version+1 WHERE id=? AND version=?',(body.nickname,body.timezone,user['id'],body.version)); c.commit(); r=c.execute('SELECT * FROM users WHERE id=?',(user['id'],)).fetchone(); c.close(); return ok({k:r[k] for k in ('id','email','nickname','timezone','version')})

@app.post('/api/v1/goals',status_code=201)
def create_goal(body:GoalCreate,user=Depends(current_user)):
    try: date.fromisoformat(body.target_date)
    except ValueError: raise HTTPException(400,'target_date 不合法')
    if body.target_date < local_date(body.timezone): raise HTTPException(400,'目标日期不能早于今天')
    c=db(); ident=uid('goal'); c.execute('INSERT INTO goals VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',(ident,user['id'],body.title,body.goal_type,body.level,body.path_template_id,body.target_date,body.daily_minutes,body.timezone,'draft',1,0,now())); c.commit(); r=c.execute('SELECT * FROM goals WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/goals')
def list_goals(status_filter:Optional[str]=Query(default=None,alias='status'),user=Depends(current_user)):
    c=db(); q='SELECT * FROM goals WHERE user_id=?'; args=[user['id']];
    if status_filter: q+=' AND status=?'; args.append(status_filter)
    rows=[rowdict(x) for x in c.execute(q+' ORDER BY created_at DESC',args)]; c.close(); return ok(rows)
@app.get('/api/v1/goals/{goal_id}')
def get_goal(goal_id:str,user=Depends(current_user)):
    c=db(); r=must_owner(c,'goals',goal_id,user['id']); c.close(); return ok(rowdict(r))
@app.patch('/api/v1/goals/{goal_id}')
def patch_goal(goal_id:str,body:GoalPatch,user=Depends(current_user)):
    c=db(); r=must_owner(c,'goals',goal_id,user['id']);
    if r['version']!=body.version: c.close(); raise HTTPException(409,'记录已更新，请重新读取')
    c.execute('UPDATE goals SET title=COALESCE(?,title),target_date=COALESCE(?,target_date),daily_minutes=COALESCE(?,daily_minutes),version=version+1 WHERE id=? AND version=?',(body.title,body.target_date,body.daily_minutes,goal_id,body.version)); c.commit(); r=c.execute('SELECT * FROM goals WHERE id=?',(goal_id,)).fetchone(); c.close(); return ok(rowdict(r))
@app.post('/api/v1/goals/{goal_id}/transitions')
def transition(goal_id:str,body:Transition,user=Depends(current_user)):
    if body.action not in ('activate','pause','resume','archive'): raise HTTPException(400,'不支持的状态操作')
    c=db(); r=must_owner(c,'goals',goal_id,user['id']);
    if r['version']!=body.version: c.close(); raise HTTPException(409,'记录已更新，请重新读取')
    valid={'activate':['draft'],'pause':['active'],'resume':['paused'],'archive':['draft','active','paused','completed']}
    if r['status'] not in valid[body.action]: c.close(); raise HTTPException(409,'目标状态不允许该操作')
    target={'activate':'active','pause':'paused','resume':'active','archive':'archived'}[body.action]
    if body.action=='activate' and r['status']!='draft': c.close(); raise HTTPException(409,'目标状态不允许激活')
    c.execute('UPDATE goals SET status=?,version=version+1,plan_version=CASE WHEN ?="active" AND plan_version=0 THEN 1 ELSE plan_version END WHERE id=?',(target,target,goal_id))
    if target=='active':
        today=local_date(r['timezone']); c.execute('INSERT OR IGNORE INTO tasks VALUES(?,?,?,?,?,?,?,?,?)',(uid('task'),goal_id,today,'study','今日学习任务',r['daily_minutes'],'pending',None,None))
    c.execute('INSERT INTO outbox VALUES(?,?,?,?,0)',(uid('evt'),'goal_'+body.action,json.dumps({'goal_id':goal_id}),now())); c.commit(); out=c.execute('SELECT * FROM goals WHERE id=?',(goal_id,)).fetchone(); c.close(); return ok(rowdict(out))
@app.get('/api/v1/goals/{goal_id}/today')
def today(goal_id:str,user=Depends(current_user)):
    c=db(); g=must_owner(c,'goals',goal_id,user['id']); d=local_date(g['timezone'])
    if g['status']=='active':
        c.execute('INSERT OR IGNORE INTO tasks VALUES(?,?,?,?,?,?,?,?,?)',(uid('task'),goal_id,d,'study','今日学习任务',g['daily_minutes'],'pending',None,None)); c.commit()
    rows=[rowdict(x) for x in c.execute('SELECT * FROM tasks WHERE goal_id=? AND local_date=?',(goal_id,d))]; c.close(); return ok({'goal_id':goal_id,'local_date':d,'timezone':g['timezone'],'plan_version':g['plan_version'],'tasks':rows})
@app.get('/api/v1/goals/{goal_id}/tasks')
def tasks(goal_id:str,user=Depends(current_user)):
    c=db(); must_owner(c,'goals',goal_id,user['id']); rows=[rowdict(x) for x in c.execute('SELECT * FROM tasks WHERE goal_id=? ORDER BY local_date DESC',(goal_id,))]; c.close(); return ok(rows)
@app.get('/api/v1/goals/{goal_id}/progress')
def progress(goal_id:str,user=Depends(current_user)):
    c=db(); g=must_owner(c,'goals',goal_id,user['id'])
    total=c.execute('SELECT COUNT(*) n FROM tasks WHERE goal_id=? AND status<>"cancelled"',(goal_id,)).fetchone()['n']
    done=c.execute('SELECT COUNT(*) n FROM tasks WHERE goal_id=? AND status="completed"',(goal_id,)).fetchone()['n']
    checkins=c.execute('SELECT COUNT(*) n FROM checkins WHERE goal_id=?',(goal_id,)).fetchone()['n']
    c.close(); risk='paused' if g['status']=='paused' else ('completed' if g['status']=='completed' else 'on_track')
    return ok({'goal_id':goal_id,'snapshot_version':1,'plan_version':g['plan_version'],'task_completion_rate':done/total if total else 0,'checkpoint_completion_rate':0,'mastery_score':None,'mastery_coverage':0,'streak_days':checkins,'risk':risk,'is_stale':False,'next_checkpoint_id':None,'computed_at':now()})
@app.post('/api/v1/tasks/{task_id}/completions')
def complete_task(task_id:str,body:Completion,user=Depends(current_user),idempotency_key:Optional[str]=Header(default=None,alias='Idempotency-Key')):
    c=db(); r=c.execute('SELECT t.*,g.user_id FROM tasks t JOIN goals g ON g.id=t.goal_id WHERE t.id=? AND g.user_id=?',(task_id,user['id'])).fetchone()
    if not r: c.close(); raise HTTPException(404,'任务不存在或不可见')
    if r['type']=='checkpoint': c.close(); raise HTTPException(409,'检查点任务只能由评估服务完成')
    c.execute('UPDATE tasks SET status="completed",evidence_level="self_reported",completed_at=? WHERE id=?',(now(),task_id)); c.commit(); out=c.execute('SELECT * FROM tasks WHERE id=?',(task_id,)).fetchone(); c.close(); return ok(rowdict(out))

@app.post('/api/v1/goals/{goal_id}/checkins',status_code=201)
def create_checkin(goal_id:str,body:Checkin,user=Depends(current_user)):
    c=db(); g=must_owner(c,'goals',goal_id,user['id']);
    if g['status']!='active': c.close(); raise HTTPException(409,'目标当前不可打卡')
    for task_id in body.task_ids:
        if not c.execute('SELECT 1 FROM tasks WHERE id=? AND goal_id=?',(task_id,goal_id)).fetchone(): c.close(); raise HTTPException(404,'任务不属于当前目标')
    if body.reflection_id:
        if not c.execute('SELECT 1 FROM reflections WHERE id=? AND goal_id=? AND user_id=?',(body.reflection_id,goal_id,user['id'])).fetchone(): c.close(); raise HTTPException(404,'总结不存在或不可见')
    d=local_date(g['timezone']); ident=uid('checkin')
    try: c.execute('INSERT INTO checkins VALUES(?,?,?,?,?,?,?,?,?)',(ident,user['id'],goal_id,d,body.minutes_spent,body.work_summary,json.dumps(body.task_ids),body.reflection_id,now())); c.execute('INSERT INTO outbox VALUES(?,?,?,?,0)',(uid('evt'),'checkin_created',json.dumps({'checkin_id':ident,'goal_id':goal_id}),now())); c.commit()
    except sqlite3.IntegrityError:
        old=c.execute('SELECT * FROM checkins WHERE user_id=? AND goal_id=? AND local_date=?',(user['id'],goal_id,d)).fetchone(); c.close()
        if old['work_summary']==body.work_summary and old['minutes_spent']==body.minutes_spent: return ok(rowdict(old))
        raise HTTPException(409,'今日已打卡且内容不同')
    r=c.execute('SELECT * FROM checkins WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/goals/{goal_id}/checkins')
def list_checkins(goal_id:str,user=Depends(current_user)):
    c=db(); must_owner(c,'goals',goal_id,user['id']); rows=[rowdict(x) for x in c.execute('SELECT * FROM checkins WHERE goal_id=? ORDER BY local_date DESC',(goal_id,))]; c.close(); return ok(rows)
@app.post('/api/v1/goals/{goal_id}/reflections',status_code=201)
def create_reflection(goal_id:str,body:Reflection,user=Depends(current_user)):
    c=db(); must_owner(c,'goals',goal_id,user['id']); ident=uid('reflection'); content=body.model_dump();
    try: c.execute('INSERT INTO reflections VALUES(?,?,?,?,?,?,?,?,?,?)',(ident,user['id'],goal_id,body.period_type,body.period_start,body.period_end,json.dumps(content,ensure_ascii=False),1,now(),now())); c.commit()
    except sqlite3.IntegrityError: c.close(); raise HTTPException(409,'该周期总结已存在')
    r=c.execute('SELECT * FROM reflections WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/goals/{goal_id}/reflections')
def list_reflections(goal_id:str,user=Depends(current_user)):
    c=db(); must_owner(c,'goals',goal_id,user['id']); rows=[rowdict(x) for x in c.execute('SELECT * FROM reflections WHERE goal_id=? ORDER BY period_start DESC',(goal_id,))]; c.close(); return ok(rows)
@app.get('/api/v1/reflections/{reflection_id}')
def get_reflection(reflection_id:str,user=Depends(current_user)):
    c=db(); r=must_owner(c,'reflections',reflection_id,user['id']); c.close(); return ok(rowdict(r))
@app.patch('/api/v1/reflections/{reflection_id}')
def patch_reflection(reflection_id:str,body:ReflectionPatch,user=Depends(current_user)):
    c=db(); r=must_owner(c,'reflections',reflection_id,user['id']);
    if body.version!=r['version']: c.close(); raise HTTPException(409,'总结版本冲突')
    c.execute('UPDATE reflections SET content=?,version=version+1,updated_at=? WHERE id=? AND version=?',(json.dumps(body.content,ensure_ascii=False),now(),reflection_id,body.version)); c.commit(); r=c.execute('SELECT * FROM reflections WHERE id=?',(reflection_id,)).fetchone(); c.close(); return ok(rowdict(r))

@app.get('/api/v1/knowledge-points')
def knowledge(keyword:Optional[str]=None, user=Depends(current_user)):
    c=db(); q='SELECT * FROM knowledge_points'; args=[]
    if keyword: q+=' WHERE title LIKE ? OR definition LIKE ?'; args=[f'%{keyword}%',f'%{keyword}%']
    rows=[rowdict(x) for x in c.execute(q,args)]; c.close(); return ok(rows)
@app.get('/api/v1/knowledge-points/{kp_id}')
def knowledge_one(kp_id:str,user=Depends(current_user)):
    c=db(); r=c.execute('SELECT * FROM knowledge_points WHERE id=?',(kp_id,)).fetchone(); c.close();
    if not r: raise HTTPException(404,'知识点不存在')
    return ok(rowdict(r))
@app.get('/api/v1/checkpoints/{checkpoint_id}')
def checkpoint(checkpoint_id:str,user=Depends(current_user)):
    c=db(); r=c.execute('SELECT * FROM checkpoints WHERE id=?',(checkpoint_id,)).fetchone(); c.close();
    if not r: raise HTTPException(404,'检查点不存在')
    return ok(rowdict(r))
@app.post('/api/v1/checkpoints/{checkpoint_id}/submissions',status_code=202)
def submit(checkpoint_id:str,body:Submission,user=Depends(current_user),idempotency_key:Optional[str]=Header(default=None,alias='Idempotency-Key')):
    if not idempotency_key: raise HTTPException(400,'提交必须带 Idempotency-Key')
    c=db();
    if not c.execute('SELECT 1 FROM checkpoints WHERE id=?',(checkpoint_id,)).fetchone(): c.close(); raise HTTPException(404,'检查点不存在')
    ident=uid('submission'); payload=json.dumps(body.model_dump(),ensure_ascii=False)
    try: c.execute('INSERT INTO submissions VALUES(?,?,?,?,?,?,?,?)',(ident,user['id'],checkpoint_id,body.attempt_id,payload,'queued',None,now())); c.execute('INSERT INTO idempotency VALUES(?,?,?,?)',(user['id'],idempotency_key,hashlib.sha256(payload.encode()).hexdigest(),json.dumps({'id':ident}))); c.commit()
    except sqlite3.IntegrityError:
        r=c.execute('SELECT * FROM submissions WHERE user_id=? AND checkpoint_id=? AND attempt_id=?',(user['id'],checkpoint_id,body.attempt_id)).fetchone(); c.close(); return ok(rowdict(r))
    r=c.execute('SELECT * FROM submissions WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/checkpoints/{checkpoint_id}/submissions')
def submissions(checkpoint_id:str,user=Depends(current_user)):
    c=db(); rows=[rowdict(x) for x in c.execute('SELECT * FROM submissions WHERE checkpoint_id=? AND user_id=? ORDER BY created_at DESC',(checkpoint_id,user['id']))]; c.close(); return ok(rows)

@app.get('/api/v1/groups')
def groups(keyword:Optional[str]=None):
    c=db(); q='SELECT * FROM groups_'; args=[]
    if keyword: q+=' WHERE title LIKE ? OR description LIKE ?'; args=[f'%{keyword}%',f'%{keyword}%']
    rows=[rowdict(x) for x in c.execute(q,args)]; c.close(); return ok(rows)
@app.post('/api/v1/groups',status_code=201)
def create_group(body:GroupCreate,user=Depends(current_user)):
    c=db(); ident=uid('group'); c.execute('INSERT INTO groups_ VALUES(?,?,?,?,?,?,?,?,?,?)',(ident,user['id'],body.title,body.description,body.goal_type,body.level,body.capacity,body.join_policy,body.visibility,now())); c.execute('INSERT INTO memberships VALUES(?,?,?,?,?,?)',(uid('mem'),ident,user['id'],'active','owner',now())); c.commit(); r=c.execute('SELECT * FROM groups_ WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/groups/{group_id}')
def get_group(group_id:str):
    c=db(); r=c.execute('SELECT * FROM groups_ WHERE id=?',(group_id,)).fetchone(); c.close();
    if not r: raise HTTPException(404,'小组不存在')
    return ok(rowdict(r))
@app.post('/api/v1/groups/{group_id}/memberships',status_code=201)
def join_group(group_id:str,user=Depends(current_user)):
    c=db(); g=c.execute('SELECT * FROM groups_ WHERE id=?',(group_id,)).fetchone();
    if not g: c.close(); raise HTTPException(404,'小组不存在')
    st='active' if g['join_policy']=='open' else 'pending'; ident=uid('mem')
    try: c.execute('INSERT INTO memberships VALUES(?,?,?,?,?,?)',(ident,group_id,user['id'],st,'member',now())); c.commit()
    except sqlite3.IntegrityError: c.close(); raise HTTPException(409,'已加入或申请该小组')
    r=c.execute('SELECT * FROM memberships WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/groups/{group_id}/posts')
def group_posts(group_id:str):
    c=db(); rows=[rowdict(x) for x in c.execute('SELECT * FROM posts WHERE group_id=? AND status="published" ORDER BY created_at DESC',(group_id,))]; c.close(); return ok(rows)
@app.post('/api/v1/posts',status_code=201)
def create_post(body:PostCreate,user=Depends(current_user)):
    c=db(); m=c.execute('SELECT 1 FROM memberships WHERE group_id=? AND user_id=? AND status="active"',(body.group_id,user['id'])).fetchone();
    if not m: c.close(); raise HTTPException(403,'请先加入小组')
    ident=uid('post'); c.execute('INSERT INTO posts VALUES(?,?,?,?,?,?,?,?,?,?)',(ident,body.group_id,user['id'],body.type,body.title,body.body,'published',1,now(),now())); c.commit(); r=c.execute('SELECT * FROM posts WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/posts/{post_id}')
def get_post(post_id:str):
    c=db(); r=c.execute('SELECT * FROM posts WHERE id=? AND status!="deleted"',(post_id,)).fetchone(); c.close();
    if not r: raise HTTPException(404,'帖子不存在或不可见')
    return ok(rowdict(r))
@app.patch('/api/v1/posts/{post_id}')
def patch_post(post_id:str,body:PostPatch,user=Depends(current_user)):
    c=db(); r=c.execute('SELECT * FROM posts WHERE id=? AND author_id=?',(post_id,user['id'])).fetchone();
    if not r: c.close(); raise HTTPException(404,'帖子不存在或不可见')
    if r['version']!=body.version: c.close(); raise HTTPException(409,'帖子版本冲突')
    c.execute('UPDATE posts SET title=COALESCE(?,title),body=COALESCE(?,body),version=version+1,updated_at=? WHERE id=? AND version=?',(body.title,body.body,now(),post_id,body.version)); c.commit(); r=c.execute('SELECT * FROM posts WHERE id=?',(post_id,)).fetchone(); c.close(); return ok(rowdict(r))
@app.delete('/api/v1/posts/{post_id}',status_code=204)
def delete_post(post_id:str,user=Depends(current_user)):
    c=db(); r=c.execute('UPDATE posts SET status="deleted",updated_at=? WHERE id=? AND author_id=?',(now(),post_id,user['id'])); c.commit(); c.close();
    if not r.rowcount: raise HTTPException(404,'帖子不存在或不可见')
    return Response(status_code=204)
@app.post('/api/v1/posts/{post_id}/comments',status_code=201)
def comment(post_id:str,body:CommentCreate,user=Depends(current_user)):
    c=db(); p=c.execute('SELECT group_id FROM posts WHERE id=? AND status="published"',(post_id,)).fetchone();
    if not p: c.close(); raise HTTPException(404,'帖子不存在')
    if not c.execute('SELECT 1 FROM memberships WHERE group_id=? AND user_id=? AND status="active"',(p['group_id'],user['id'])).fetchone(): c.close(); raise HTTPException(403,'无权评论')
    ident=uid('comment'); c.execute('INSERT INTO comments VALUES(?,?,?,?,?,?)',(ident,post_id,user['id'],body.body,body.parent_id,now())); c.commit(); r=c.execute('SELECT * FROM comments WHERE id=?',(ident,)).fetchone(); c.close(); return ok(rowdict(r))
@app.get('/api/v1/posts/{post_id}/comments')
def comments(post_id:str):
    c=db(); rows=[rowdict(x) for x in c.execute('SELECT * FROM comments WHERE post_id=? ORDER BY created_at',(post_id,))]; c.close(); return ok(rows)

# 局部联网群组：这是原型中的独立边界。每个群组可以指向自己的微服务地址，
# 成员通过一次性显示的 join_key 加入；在线状态只由心跳更新时间决定。
@app.get('/api/v1/community/groups')
def local_group_catalog():
    c=db(); rows=[rowdict(x) for x in c.execute('SELECT id,name,description,service_url,created_at,updated_at FROM local_groups ORDER BY updated_at DESC')]; c.close(); return ok(rows)
@app.post('/api/v1/community/groups',status_code=201)
def create_local_group(body:LocalGroupCreate,user=Depends(current_user)):
    ident=uid('lgroup'); key=secrets.token_urlsafe(18); ts=now(); c=db()
    c.execute('INSERT INTO local_groups VALUES(?,?,?,?,?,?,?,?)',(ident,user['id'],body.name,body.description,hashlib.sha256(key.encode()).hexdigest(),body.service_url,ts,ts))
    c.execute('INSERT INTO local_group_members VALUES(?,?,?,?,?,?,?,?)',(uid('lgm'),ident,user['id'],user['nickname'],None,'owner','online',ts)); c.commit(); c.close()
    return ok({'id':ident,'name':body.name,'description':body.description,'service_url':body.service_url,'join_key':key,'join_key_notice':'仅创建时返回，请通过安全渠道分享'})
@app.post('/api/v1/community/groups/{group_id}/join',status_code=201)
def join_local_group(group_id:str,body:LocalGroupJoin,user=Depends(current_user)):
    c=db(); g=c.execute('SELECT * FROM local_groups WHERE id=?',(group_id,)).fetchone()
    if not g: c.close(); raise HTTPException(404,'局部联网群组不存在')
    if not hmac.compare_digest(g['join_key_hash'],hashlib.sha256(body.join_key.encode()).hexdigest()): c.close(); raise HTTPException(403,'联网密钥无效')
    ts=now()
    try: c.execute('INSERT INTO local_group_members VALUES(?,?,?,?,?,?,?,?)',(uid('lgm'),group_id,user['id'],body.host_name,body.host_address,'member','online',ts)); c.commit()
    except sqlite3.IntegrityError: c.execute('UPDATE local_group_members SET host_name=?,host_address=?,status="online",last_seen_at=? WHERE group_id=? AND user_id=?',(body.host_name,body.host_address,ts,group_id,user['id'])); c.commit()
    r=c.execute('SELECT id,group_id,user_id,host_name,host_address,role,status,last_seen_at FROM local_group_members WHERE group_id=? AND user_id=?',(group_id,user['id'])).fetchone(); c.close(); return ok(rowdict(r))
@app.post('/api/v1/community/groups/{group_id}/heartbeat')
def local_group_heartbeat(group_id:str,body:LocalGroupHeartbeat,user=Depends(current_user)):
    c=db(); r=c.execute('SELECT * FROM local_group_members WHERE group_id=? AND user_id=?',(group_id,user['id'])).fetchone()
    if not r: c.close(); raise HTTPException(404,'你尚未加入该局部联网群组')
    c.execute('UPDATE local_group_members SET host_name=COALESCE(?,host_name),host_address=COALESCE(?,host_address),status="online",last_seen_at=? WHERE group_id=? AND user_id=?',(body.host_name,body.host_address,now(),group_id,user['id'])); c.commit(); out=c.execute('SELECT * FROM local_group_members WHERE group_id=? AND user_id=?',(group_id,user['id'])).fetchone(); c.close(); return ok(rowdict(out))
@app.get('/api/v1/community/groups/{group_id}/members')
def local_group_members(group_id:str,user=Depends(current_user)):
    c=db(); member=c.execute('SELECT 1 FROM local_group_members WHERE group_id=? AND user_id=?',(group_id,user['id'])).fetchone()
    if not member: c.close(); raise HTTPException(404,'群组不存在或你无权查看成员')
    stale=int(time.time())-90; c.execute("UPDATE local_group_members SET status='offline' WHERE group_id=? AND CAST(strftime('%s',last_seen_at) AS INTEGER)<?",(group_id,stale)); c.commit()
    rows=[rowdict(x) for x in c.execute('SELECT id,user_id,host_name,host_address,role,status,last_seen_at FROM local_group_members WHERE group_id=? ORDER BY status DESC,last_seen_at DESC',(group_id,))]; c.close(); return ok({'group_id':group_id,'members':rows,'synced_at':now()})
@app.delete('/api/v1/community/groups/{group_id}/membership',status_code=204)
def leave_local_group(group_id:str,user=Depends(current_user)):
    c=db(); r=c.execute('DELETE FROM local_group_members WHERE group_id=? AND user_id=?',(group_id,user['id'])); c.commit(); c.close()
    if not r.rowcount: raise HTTPException(404,'群组成员关系不存在')
    return Response(status_code=204)

@app.get('/api/v1/notifications')
def notifications(unread:bool=False,user=Depends(current_user)):
    c=db(); q='SELECT * FROM notifications WHERE user_id=?'; args=[user['id']];
    if unread: q+=' AND read=0'
    rows=[rowdict(x) for x in c.execute(q+' ORDER BY created_at DESC',args)]; c.close(); return ok(rows)
@app.patch('/api/v1/notifications/{notification_id}')
def mark_notification(notification_id:str,user=Depends(current_user)):
    c=db(); c.execute('UPDATE notifications SET read=1 WHERE id=? AND user_id=?',(notification_id,user['id'])); c.commit(); r=c.execute('SELECT * FROM notifications WHERE id=? AND user_id=?',(notification_id,user['id'])).fetchone(); c.close();
    if not r: raise HTTPException(404,'通知不存在')
    return ok(rowdict(r))

from app.workspace import install_workspace
install_workspace(app, db, uid, now, ok, current_user)
app.mount('/static',StaticFiles(directory=os.path.join(os.path.dirname(__file__),'static')),name='static')
