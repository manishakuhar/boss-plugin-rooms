-- Requires BOSS organisations/organisation_members and Supabase auth.uid().
-- Run through the deployment owner's normal migration process; never from a plugin.
begin;
create table public.boss_rooms (
 id uuid primary key default gen_random_uuid(), org_id uuid not null references public.organisations(id),
 name text not null check(length(name) between 1 and 80), kind text not null check(kind in ('room','direct','assistant')),
 visibility text not null default 'private' check(visibility in ('private','organization')),
 owner_id uuid not null references auth.users(id), members uuid[] not null,
 participant_key text, created_at timestamptz not null default now(),
 check(cardinality(members)>0), check(owner_id=any(members)),
 check(kind='room' or visibility='private'), check(kind<>'assistant' or cardinality(members)=1)
);
create unique index boss_rooms_participants on public.boss_rooms(org_id,kind,participant_key) where kind in ('direct','assistant');
create table public.boss_room_messages (
 id uuid primary key default gen_random_uuid(), seq bigint generated always as identity unique,
 room_id uuid not null references public.boss_rooms(id), parent_id uuid references public.boss_room_messages(id),
 author_id uuid not null references auth.users(id), author_kind text not null default 'human' check(author_kind in ('human','assistant')),
 body text not null check(length(body)<=16000), request_id uuid not null,
 revision integer not null default 1, deleted boolean not null default false, pinned boolean not null default false,
 created_at timestamptz not null default now(), unique(author_id,request_id)
);
create index boss_room_messages_page on public.boss_room_messages(room_id,parent_id,seq desc);
create table public.boss_room_memory (
 org_id uuid not null references public.organisations(id), user_id uuid not null references auth.users(id),
 body text not null check(length(body)<=8000), revision integer not null default 1,
 primary key(org_id,user_id)
);
-- RPC-only access: even guessed table/message IDs cannot bypass the checks below.
alter table public.boss_rooms enable row level security;
alter table public.boss_room_messages enable row level security;
alter table public.boss_room_memory enable row level security;
revoke all on public.boss_rooms, public.boss_room_messages, public.boss_room_memory from public, anon, authenticated;
revoke all on sequence public.boss_room_messages_seq_seq from public, anon, authenticated;

create function public.boss_rooms_v1(operation text, payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path=pg_catalog,public as $$
#variable_conflict use_column
<<v>>
declare
 actor uuid := auth.uid(); org uuid; rid uuid; parent uuid; members uuid[]; row_room public.boss_rooms;
 msg public.boss_room_messages; existing public.boss_room_messages; result jsonb;
 kind text; body text; name text; req uuid; lim integer; before_seq bigint; expected integer;
begin
 if actor is null then raise exception 'Sign in required' using errcode='42501'; end if;
 if operation='organizations' then
  select coalesce(jsonb_agg(jsonb_build_object('id',o.id,'name',o.name) order by o.name),'[]') into result
  from public.organisations o join public.organisation_members m on m.org_id=o.id where m.user_id=actor and m.status='active';
  return result;
 end if;
 org := (payload->>'org_id')::uuid;
 -- Holding this membership lock serializes an in-flight call against revocation.
 perform 1 from public.organisation_members where org_id=org and user_id=actor and status='active' for share;
 if not found then raise exception 'Organization membership required' using errcode='42501'; end if;
 if operation='directory' then
  select coalesce(jsonb_agg(jsonb_build_object('id',u.id,'name',coalesce(nullif(u.raw_user_meta_data->>'full_name',''),u.email)) order by u.email),'[]') into result
  from public.organisation_members m join auth.users u on u.id=m.user_id where m.org_id=org and m.status='active';
  return result;
 elsif operation='memory' then
  select jsonb_build_object('body',body,'revision',revision) into result from public.boss_room_memory where org_id=org and user_id=actor;
  return coalesce(result,'{"body":"","revision":0}');
 elsif operation='save_memory' then
  body := coalesce(payload->>'body',''); expected := (payload->>'revision')::integer;
  if length(body)>8000 or expected is null then raise exception 'Invalid memory'; end if;
  if expected=0 then
   insert into public.boss_room_memory values(org,actor,body,1) on conflict do nothing;
  else
   update public.boss_room_memory set body=v.body,revision=revision+1 where org_id=org and user_id=actor and revision=expected;
  end if;
  if not found then raise exception 'Memory changed; reload before saving' using errcode='40001'; end if;
  return jsonb_build_object('body',body,'revision',expected+1);
 elsif operation='list' then
  select coalesce(jsonb_agg(to_jsonb(r) || case when r.kind='direct' then jsonb_build_object('name',
    (select string_agg(coalesce(nullif(u.raw_user_meta_data->>'full_name',''),u.email), ', ' order by u.id) from auth.users u where u.id=any(r.members) and u.id<>actor)) else '{}'::jsonb end order by r.name),'[]') into result from public.boss_rooms r
  where r.org_id=org and (actor=any(r.members) or (r.kind='room' and r.visibility='organization'));
  return result;
 elsif operation='create' then
  kind:=payload->>'kind'; name:=trim(payload->>'name');
  if kind not in ('room','direct','assistant') or kind is null then raise exception 'Invalid conversation type'; end if;
  select array_agg(distinct v order by v) into members from (select actor v union all select value::uuid from jsonb_array_elements_text(coalesce(payload->'members','[]'))) s;
  if cardinality(members)>100 then raise exception 'Maximum 100 members'; end if;
  if kind='assistant' then members:=array[actor]; name:='My assistant'; end if;
  if kind='direct' and cardinality(members)<2 then raise exception 'Choose at least one other member'; end if;
  if exists(select 1 from unnest(members) member where not exists(select 1 from public.organisation_members m where m.org_id=org and m.user_id=member and m.status='active')) then raise exception 'All participants must be active organization members' using errcode='42501'; end if;
  if name is null or length(name) not between 1 and 80 then raise exception 'Name must be 1–80 characters'; end if;
  insert into public.boss_rooms(org_id,name,kind,visibility,owner_id,members,participant_key)
  values(org,name,kind,case when kind='room' then coalesce(payload->>'visibility','private') else 'private' end,actor,members,array_to_string(members,','))
  on conflict (org_id,kind,participant_key) where kind in ('direct','assistant') do update set participant_key=excluded.participant_key
  returning * into row_room;
  return to_jsonb(row_room);
 end if;
 rid:=(payload->>'room_id')::uuid;
 select * into row_room from public.boss_rooms where id=rid and org_id=org for update;
 if not found then raise exception 'Conversation unavailable' using errcode='42501'; end if;
 if operation='join' and row_room.kind='room' and row_room.visibility='organization' then
  if not actor=any(row_room.members) then update public.boss_rooms set members=array_append(boss_rooms.members,actor) where id=rid returning * into row_room; end if;
  return to_jsonb(row_room);
 end if;
 if not actor=any(row_room.members) then raise exception 'Conversation membership required' using errcode='42501'; end if;
 if operation='leave' then
  if row_room.kind<>'room' or row_room.owner_id=actor then raise exception 'Transfer ownership before leaving'; end if;
  update public.boss_rooms set members=array_remove(boss_rooms.members,actor) where id=rid;
  return '{"ok":true}';
 elsif operation='update' then
  if row_room.kind<>'room' or row_room.owner_id<>actor then raise exception 'Room owner required' using errcode='42501'; end if;
  select array_agg(distinct value::uuid order by value::uuid) into members from jsonb_array_elements_text(payload->'members');
  if cardinality(members) is null or cardinality(members)>100 or not actor=any(members) then raise exception 'Invalid members'; end if;
  if exists(select 1 from unnest(members) member where not exists(select 1 from public.organisation_members m where m.org_id=org and m.user_id=member and m.status='active')) then raise exception 'Invalid organization member' using errcode='42501'; end if;
  update public.boss_rooms set name=trim(payload->>'name'),members=v.members,owner_id=(payload->>'owner_id')::uuid where id=rid returning * into row_room;
  return to_jsonb(row_room);
 elsif operation='message' then
  select to_jsonb(m) into result from public.boss_room_messages m where m.id=(payload->>'message_id')::uuid and m.room_id=rid;
  if result is null then raise exception 'Message unavailable'; end if;
  return result;
 elsif operation='messages' then
  parent:=(payload->>'parent_id')::uuid; lim:=least(100,greatest(1,coalesce((payload->>'limit')::integer,100)));before_seq:=coalesce((payload->>'before')::bigint,9223372036854775807);
  if parent is not null and not exists(select 1 from public.boss_room_messages where id=parent and room_id=rid and parent_id is null) then raise exception 'Invalid thread'; end if;
  select coalesce(jsonb_agg(to_jsonb(s) order by seq),'[]') into result from (select * from public.boss_room_messages where room_id=rid and parent_id is not distinct from parent and seq<before_seq order by seq desc limit lim) s;
  return result;
 elsif operation='search' or operation='pins' then
  if operation='search' and length(trim(coalesce(payload->>'query','')))<1 then return '[]'; end if;
  select coalesce(jsonb_agg(to_jsonb(s) order by seq desc),'[]') into result from (select * from public.boss_room_messages where room_id=rid and not deleted and (case when operation='pins' then pinned else position(lower(payload->>'query') in lower(boss_room_messages.body))>0 end) order by seq desc limit 100) s;
  return result;
 elsif operation='post' then
  body:=trim(payload->>'body');req:=(payload->>'request_id')::uuid;parent:=(payload->>'parent_id')::uuid;kind:=coalesce(payload->>'author_kind','human');
  if body is null or length(body) not between 1 and 16000 or req is null or kind not in ('human','assistant') then raise exception 'Invalid message'; end if;
  if parent is not null and not exists(select 1 from public.boss_room_messages where id=parent and room_id=rid and parent_id is null) then raise exception 'Invalid thread'; end if;
  insert into public.boss_room_messages(room_id,parent_id,author_id,body,request_id,author_kind) values(rid,parent,actor,body,req,kind)
  on conflict(author_id,request_id) do nothing returning * into msg;
  if not found then
   select * into msg from public.boss_room_messages where author_id=actor and request_id=req;
   if msg.room_id<>rid or msg.parent_id is distinct from parent or msg.body<>body or msg.author_kind<>kind then raise exception 'Request ID already used for different content'; end if;
  end if;
  return to_jsonb(msg);
 elsif operation in ('edit','delete','pin') then
  select * into msg from public.boss_room_messages where id=(payload->>'message_id')::uuid and room_id=rid for update;
  if not found then raise exception 'Message unavailable'; end if;
  if operation in ('edit','delete') and msg.author_id<>actor then raise exception 'Only the author can change this message' using errcode='42501'; end if;
  if (payload->>'revision')::integer is distinct from msg.revision then raise exception 'Message changed; reload before editing' using errcode='40001'; end if;
  if msg.deleted then raise exception 'Message is deleted'; end if;
  body:=trim(payload->>'body');
  if operation='edit' and (body is null or length(body) not between 1 and 16000) then raise exception 'Invalid message'; end if;
  update public.boss_room_messages set
   body=case when operation='delete' then '' when operation='edit' then v.body else boss_room_messages.body end,
   deleted=operation='delete',pinned=case when operation='delete' then false when operation='pin' then coalesce((payload->>'pinned')::boolean,false) else boss_room_messages.pinned end,
   revision=revision+1 where id=msg.id returning * into msg;
  return to_jsonb(msg);
 else raise exception 'Unknown operation'; end if;
end;
$$;
revoke all on function public.boss_rooms_v1(text,jsonb) from public,anon;
grant execute on function public.boss_rooms_v1(text,jsonb) to authenticated;
commit;
