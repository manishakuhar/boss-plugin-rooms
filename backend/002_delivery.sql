-- Additive migration. Apply after 001_rooms.sql through the deployment owner.
begin;
create table public.boss_room_reads (
 room_id uuid not null references public.boss_rooms(id), user_id uuid not null references auth.users(id),
 thread_key text not null default '', last_seq bigint not null default 0 check(last_seq>=0),
 primary key(room_id,user_id,thread_key)
);
create table public.boss_room_notification_preferences (
 room_id uuid not null references public.boss_rooms(id), user_id uuid not null references auth.users(id),
 mode text not null default 'mentions' check(mode in ('mentions','all','mute')),
 primary key(room_id,user_id)
);
create table public.boss_room_mentions (
 message_id uuid not null references public.boss_room_messages(id), user_id uuid not null references auth.users(id),
 primary key(message_id,user_id)
);
alter table public.boss_room_reads enable row level security;
alter table public.boss_room_notification_preferences enable row level security;
alter table public.boss_room_mentions enable row level security;
revoke all on public.boss_room_reads, public.boss_room_notification_preferences, public.boss_room_mentions from public,anon,authenticated;
alter function public.boss_rooms_v1(text,jsonb) rename to boss_rooms_core_v1;
revoke all on function public.boss_rooms_core_v1(text,jsonb) from public,anon,authenticated;
create function public.boss_rooms_v1(operation text,payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path=pg_catalog,public as $$
#variable_conflict use_column
declare
 actor uuid := auth.uid(); org uuid := (payload->>'org_id')::uuid; rid uuid := (payload->>'room_id')::uuid;
 room public.boss_rooms; result jsonb; target bigint; thread text := coalesce(payload->>'parent_id','');
 mentioned uuid[]; existing_mentions uuid[];
begin
 if operation not in ('inbox','mark_read','notification_preference','post') then
  return public.boss_rooms_core_v1(operation,payload);
 end if;
 perform 1 from public.organisation_members where org_id=org and user_id=actor and status='active' for share;
 if not found then raise exception 'Membership required' using errcode='42501'; end if;
 if operation='inbox' then
  select coalesce(jsonb_agg(jsonb_build_object('room_id',r.id,'unread',(
   select count(*) from public.boss_room_messages m where m.room_id=r.id and not m.deleted
    and (m.author_id<>actor or m.author_kind='assistant') and m.seq>coalesce((select last_seq from public.boss_room_reads rd where rd.room_id=r.id and rd.user_id=actor and rd.thread_key=coalesce(m.parent_id::text,'')),0)),
   'mode',coalesce(p.mode,'mentions'), 'events',(
   select coalesce(jsonb_agg(to_jsonb(e) order by e.seq),'[]') from (
    select m.* from public.boss_room_messages m where m.room_id=r.id and not m.deleted
     and (m.author_id<>actor or m.author_kind='assistant')
     and m.seq>coalesce((select last_seq from public.boss_room_reads rd where rd.room_id=r.id and rd.user_id=actor and rd.thread_key=coalesce(m.parent_id::text,'')),0)
     and coalesce(p.mode,'mentions')<>'mute'
     and (r.kind in ('direct','assistant') or p.mode='all' or exists(select 1 from public.boss_room_mentions x where x.message_id=m.id and x.user_id=actor))
    order by m.seq desc limit 100) e)) order by r.id),'[]') into result
   from public.boss_rooms r left join public.boss_room_notification_preferences p on p.room_id=r.id and p.user_id=actor
   where r.org_id=org and actor=any(r.members);
  return result;
 end if;
 select * into room from public.boss_rooms where id=rid and org_id=org for update;
 if not found or not actor=any(room.members) then raise exception 'Conversation membership required' using errcode='42501'; end if;
 if operation='mark_read' then
  target:=(payload->>'seq')::bigint;
  if target is null or not exists(select 1 from public.boss_room_messages where room_id=rid and seq=target and coalesce(parent_id::text,'')=thread) then raise exception 'Invalid read position'; end if;
  insert into public.boss_room_reads values(rid,actor,thread,target) on conflict(room_id,user_id,thread_key)
   do update set last_seq=greatest(boss_room_reads.last_seq,excluded.last_seq);
  return '{"ok":true}';
 elsif operation='notification_preference' then
  insert into public.boss_room_notification_preferences values(rid,actor,payload->>'mode') on conflict(room_id,user_id) do update set mode=excluded.mode;
  return '{"ok":true}';
 end if;
 select coalesce(array_agg(distinct value::uuid order by value::uuid),'{}') into mentioned from jsonb_array_elements_text(coalesce(payload->'mentions','[]'));
 if cardinality(mentioned)>100 or exists(select 1 from unnest(mentioned) id where not id=any(room.members) or not exists(select 1 from public.organisation_members where org_id=org and user_id=id and status='active')) then raise exception 'Invalid mention'; end if;
 if coalesce(payload->>'author_kind','human')='assistant' and cardinality(mentioned)>0 then raise exception 'Assistant mentions require explicit user action'; end if;
 select coalesce(array_agg(user_id order by user_id),'{}') into existing_mentions from public.boss_room_mentions where message_id=(select id from public.boss_room_messages where author_id=actor and request_id=(payload->>'request_id')::uuid);
 if exists(select 1 from public.boss_room_messages where author_id=actor and request_id=(payload->>'request_id')::uuid) and existing_mentions<>mentioned then raise exception 'Retry mentions changed'; end if;
 result:=public.boss_rooms_core_v1(operation,payload);
 insert into public.boss_room_mentions select (result->>'id')::uuid,unnest(mentioned) on conflict do nothing;
 return result;
end;
$$;
revoke all on function public.boss_rooms_v1(text,jsonb) from public,anon;
grant execute on function public.boss_rooms_v1(text,jsonb) to authenticated;
commit;
