"""Local group workspace APIs, isolated from the legacy public community."""
from typing import Optional
from contextlib import contextmanager
from fastapi import Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

class Discussion(BaseModel):
    title: str = Field(min_length=1,max_length=100)
    body: str = Field(min_length=1,max_length=20000)
    type: str = 'question'

def install_workspace(app, db, uid, now, ok, current_user):
    @contextmanager
    def transaction():
        c=db()
        try:
            with c:
                yield c
        finally: c.close()

    with transaction() as c:
        c.execute('CREATE TABLE IF NOT EXISTS local_discussions(id TEXT PRIMARY KEY, group_id TEXT NOT NULL,author_id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,type TEXT NOT NULL,created_at TEXT NOT NULL)')
        c.execute('CREATE INDEX IF NOT EXISTS idx_local_discussions ON local_discussions(group_id,created_at)')

    def member(c, gid, user):
        if not c.execute('SELECT 1 FROM local_group_members WHERE group_id=? AND user_id=?',(gid,user['id'])).fetchone():
            raise HTTPException(404,'群组不存在或你尚未加入')

    @app.get('/api/v1/community/my-groups')
    def my_groups(user=Depends(current_user)):
        with transaction() as c:
            rows=c.execute('SELECT g.id,g.name,g.description,g.service_url,m.role FROM local_groups g JOIN local_group_members m ON g.id=m.group_id WHERE m.user_id=? ORDER BY g.created_at DESC',(user['id'],)).fetchall()
        return ok([dict(r) for r in rows])

    @app.post('/api/v1/community/groups/{gid}/enter')
    def enter(gid:str,request:Request,user=Depends(current_user)):
        with transaction() as c:
            member(c,gid,user)
            # Only expose the peer's actual address to authenticated fellow members.
            c.execute('UPDATE local_group_members SET status="online",host_address=?,last_seen_at=? WHERE group_id=? AND user_id=?',(request.client.host,now(),gid,user['id']))
            g=dict(c.execute('SELECT id,name,description,service_url FROM local_groups WHERE id=?',(gid,)).fetchone())
        return ok(g)

    @app.get('/api/v1/community/groups/{gid}/discussions')
    def discussions(gid:str,user=Depends(current_user)):
        with transaction() as c:
            member(c,gid,user)
            rows=c.execute('SELECT d.*,u.nickname FROM local_discussions d JOIN users u ON u.id=d.author_id WHERE d.group_id=? ORDER BY d.created_at DESC LIMIT 100',(gid,)).fetchall()
        return ok([dict(r) for r in rows])

    @app.post('/api/v1/community/groups/{gid}/discussions',status_code=201)
    def publish(gid:str,body:Discussion,user=Depends(current_user)):
        with transaction() as c:
            member(c,gid,user)
            ident=uid('discussion')
            c.execute('INSERT INTO local_discussions VALUES(?,?,?,?,?,?,?)',(ident,gid,user['id'],body.title,body.body,body.type,now()))
        return ok({'id':ident})

    @app.delete('/api/v1/community/groups/{gid}/discussions/{ident}',status_code=204)
    def remove(gid:str,ident:str,user=Depends(current_user)):
        with transaction() as c:
            member(c,gid,user)
            r=c.execute('DELETE FROM local_discussions WHERE id=? AND group_id=? AND author_id=?',(ident,gid,user['id']))
            if not r.rowcount: raise HTTPException(404,'讨论不存在或无删除权限')
        return Response(status_code=204)
