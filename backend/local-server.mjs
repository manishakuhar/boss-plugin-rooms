import { PGlite } from '@electric-sql/pglite';
import { createServer } from 'node:http';
import { randomBytes, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, rename, chmod, rm } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const demoOrg = '10000000-0000-0000-0000-000000000001';
export const demoUsers = [
  { key: 'manisha', id: '00000000-0000-0000-0000-000000000001', name: 'Manisha (test)', email: 'manisha@example.test' },
  { key: 'maya', id: '00000000-0000-0000-0000-000000000002', name: 'Maya (test)', email: 'maya@example.test' },
  { key: 'leo', id: '00000000-0000-0000-0000-000000000003', name: 'Leo (test)', email: 'leo@example.test' },
];

export async function startLocalServer({ directory, port = 0 } = {}) {
  if (!directory) throw new Error('A dedicated local database directory is required.');
  directory = resolve(directory);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
  const lock = resolve(directory, 'server.lock');
  try { await mkdir(lock); } catch { throw new Error('This database is already open, or a previous server stopped unexpectedly. Stop that server before removing server.lock.'); }
  let db;
  try {
  db = new PGlite(resolve(directory, 'postgres'));
  await db.waitReady;
  const migration = await readFile(new URL('./001_rooms.sql', import.meta.url), 'utf8');
  const schemaHash = createHash('sha256').update(migration).digest('hex');
  const initialized = (await db.query("select to_regclass('public.local_rooms_schema') as name")).rows[0].name;
  if (!initialized) {
    // Only the auth/organization fixture is test-specific. Rooms uses the unchanged migration.
    await db.exec(`begin;
      create schema auth;
      create role anon;
      create role authenticated;
      create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}');
      create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('test.actor',true),'')::uuid$$;
      create table public.organisations(id uuid primary key,name text);
      create table public.organisation_members(org_id uuid,user_id uuid,status text);
      create table public.local_rooms_schema(hash text not null);
    `);
    try {
      await db.query('insert into public.organisations values($1,$2)', [demoOrg, 'Acorn Studio (local test)']);
      for (const user of demoUsers) {
        await db.query('insert into auth.users values($1,$2,$3)', [user.id, user.email, JSON.stringify({full_name:user.name})]);
        await db.query("insert into public.organisation_members values($1,$2,'active')", [demoOrg,user.id]);
      }
      // Keep fixture and unchanged migration atomic; outer transaction owns commit.
      await db.exec(migration.replace(/^begin;$/m, '').replace(/^commit;$/m, ''));
      await db.query('insert into public.local_rooms_schema values($1)', [schemaHash]);
      await db.exec('commit');
    } catch (error) { await db.exec('rollback'); await db.close(); throw error; }
  } else {
    const saved = (await db.query('select hash from public.local_rooms_schema')).rows[0].hash;
    if (saved !== schemaHash) { await db.close(); throw new Error('The Rooms schema changed. Use a new local data directory or an explicit migration; existing messages were not changed.'); }
  }
  // A second fictional organization makes the switcher testable without real accounts.
  const secondOrg = '10000000-0000-0000-0000-000000000002';
  await db.query('insert into public.organisations values($1,$2) on conflict(id) do nothing', [secondOrg, 'Northstar Lab (local test)']);
  for (const user of demoUsers.slice(0,2)) {
    await db.query("insert into public.organisation_members select $1::uuid,$2::uuid,'active' where not exists(select 1 from public.organisation_members where org_id=$1::uuid and user_id=$2::uuid)", [secondOrg,user.id]);
  }
  const users = demoUsers.map(user => ({ ...user, token: randomBytes(32).toString('hex') }));
  const actors = new Map(users.map(user => [user.token,user.id]));
  let queue = Promise.resolve();
  const serialize = job => {
    const result = queue.then(job);
    queue = result.catch(()=>{});
    return result;
  };
  const server = createServer(async (req,res) => {
    res.setHeader('Content-Type','application/json');
    res.setHeader('Cache-Control','no-store');
    const reply = (status, body) => { if (!res.destroyed) {res.writeHead(status);res.end(JSON.stringify(body));} };
    // No browser-origin access, wildcard CORS, caller-supplied identity, or arbitrary SQL.
    if (req.headers.origin || !['127.0.0.1','localhost'].includes((req.headers.host || '').split(':')[0])) return reply(403,{error:'Local native clients only'});
    if (req.method==='GET' && req.url==='/health') return reply(200,{ready:true,mode:'local-test',schemaHash});
    if (req.method!=='POST' || req.url!=='/rpc/boss_rooms_v1') return reply(404,{error:'Not found'});
    const actor = actors.get((req.headers.authorization||'').replace(/^Bearer /,''));
    if (!actor) return reply(401,{error:'Invalid local test session'});
    if (!(req.headers['content-type']||'').startsWith('application/json')) return reply(415,{error:'JSON required'});
    try {
      let body='';let bytes=0;
      for await (const chunk of req) { bytes+=chunk.length;if(bytes>128*1024)return reply(413,{error:'Request too large'});body+=chunk; }
      let data;try{data=JSON.parse(body);}catch{return reply(400,{error:'Invalid JSON'});}
      if(typeof data.operation!=='string'||!data.payload||typeof data.payload!=='object'||Array.isArray(data.payload))return reply(400,{error:'Invalid RPC request'});
      const result=await serialize(()=>db.transaction(async tx=>{
        await tx.query("select set_config('test.actor',$1,true)",[actor]);
        await tx.exec('set local role authenticated');
        return (await tx.query('select public.boss_rooms_v1($1,$2::jsonb) as value',[data.operation,JSON.stringify(data.payload)])).rows[0].value;
      }));
      reply(200,result);
    } catch(error) {
      const status=error.code==='42501'?403:error.code==='40001'?409:400;
      reply(status,{error:status===403?'Access denied':status===409?'Changed elsewhere; reload before saving':'Database rejected the request'});
    }
  });
  server.requestTimeout=15000;server.headersTimeout=10000;
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
  const url=`http://127.0.0.1:${server.address().port}`;
  const connectionFile=resolve(directory,'connection.json');
  const connection={mode:'local-test',url,schemaHash,organization:demoOrg,users};
  const temporary=connectionFile+'.tmp';await writeFile(temporary,JSON.stringify(connection,null,2),{mode:0o600});await rename(temporary,connectionFile);
  return {url,users,connectionFile,close:async()=>{await new Promise(resolve=>server.close(resolve));await queue;await db.close();await rm(connectionFile,{force:true});await rm(lock,{recursive:true,force:true});}};
  } catch(error) { await db?.close().catch(()=>{});await rm(lock,{recursive:true,force:true});throw error; }
}

if (process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const directory=process.env.ROOMS_DEMO_DATA || resolve(dirname(fileURLToPath(import.meta.url)),'../.local-rooms');
  const running=await startLocalServer({directory});
  console.log(`Local Rooms database ready at ${running.url}. Fictional accounts only. Connection file: ${running.connectionFile}`);
  let stopping=false;const stop=async()=>{if(stopping)return;stopping=true;await running.close();process.exit(0)};
  process.on('SIGTERM',stop);process.on('SIGINT',stop);
}
