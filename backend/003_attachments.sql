begin;
create table public.boss_room_attachments (
 id uuid primary key, room_id uuid not null references public.boss_rooms(id), owner_id uuid not null references auth.users(id),
 message_id uuid references public.boss_room_messages(id), name text not null check(length(name) between 1 and 180 and name !~ '[/\\\\]'),
 size bigint not null check(size between 1 and 10485760), mime text not null check(mime in ('image/png','image/jpeg','application/pdf','text/plain')),
 status text not null default 'uploading' check(status in ('uploading','ready','cancelled')),
 created_at timestamptz not null default now()
);
alter table public.boss_room_attachments enable row level security;
revoke all on public.boss_room_attachments from public,anon,authenticated;
alter function public.boss_rooms_v1(text,jsonb) rename to boss_rooms_delivery_v1;
revoke all on function public.boss_rooms_delivery_v1(text,jsonb) from public,anon,authenticated;
create function public.boss_rooms_v1(operation text,payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path=pg_catalog,public as $$
#variable_conflict use_column
declare actor uuid:=auth.uid(); org uuid:=(payload->>'org_id')::uuid; rid uuid:=(payload->>'room_id')::uuid;
 room public.boss_rooms; file public.boss_room_attachments; result jsonb; file_ids uuid[];
begin
 if operation not in ('attachment_begin','attachment_cancel','attachment_access','attachments','post') then return public.boss_rooms_delivery_v1(operation,payload); end if;
 perform 1 from public.organisation_members where org_id=org and user_id=actor and status='active' for share;
 if not found then raise exception 'Membership required' using errcode='42501'; end if;
 select * into room from public.boss_rooms where id=rid and org_id=org for update;
 if not found or not actor=any(room.members) then raise exception 'Conversation membership required' using errcode='42501'; end if;
 if operation='attachments' then
  select coalesce(jsonb_agg(to_jsonb(a) order by a.created_at),'[]') into result from public.boss_room_attachments a join public.boss_room_messages m on m.id=a.message_id
   where a.room_id=rid and a.status='ready' and not m.deleted;
  return result;
 elsif operation='attachment_begin' then
  if (select count(*) from public.boss_room_attachments where owner_id=actor and message_id is null and status<>'cancelled')>=20 then raise exception 'Too many pending uploads'; end if;
  insert into public.boss_room_attachments(id,room_id,owner_id,name,size,mime) values((payload->>'id')::uuid,rid,actor,payload->>'name',(payload->>'size')::bigint,payload->>'mime') on conflict(id) do nothing returning * into file;
  if not found then
   select * into file from public.boss_room_attachments where id=(payload->>'id')::uuid;
   if file.owner_id<>actor or file.room_id<>rid or file.name<>payload->>'name' or file.size<>(payload->>'size')::bigint or file.mime<>payload->>'mime' or file.status='cancelled' then raise exception 'Upload identity changed'; end if;
  end if;
  return to_jsonb(file);
 elsif operation in ('attachment_cancel','attachment_access') then
  select * into file from public.boss_room_attachments where id=(payload->>'id')::uuid and room_id=rid for update;
  if not found or file.status='cancelled' then raise exception 'Attachment unavailable' using errcode='42501'; end if;
  if file.message_id is null and file.owner_id<>actor then raise exception 'Private draft' using errcode='42501'; end if;
  if file.message_id is not null and exists(select 1 from public.boss_room_messages where id=file.message_id and deleted) then raise exception 'Attachment removed' using errcode='42501'; end if;
  if operation='attachment_cancel' then
   if file.owner_id<>actor or file.message_id is not null then raise exception 'Cannot cancel posted attachment'; end if;
   update public.boss_room_attachments set status='cancelled' where id=file.id;
  end if;
  return to_jsonb(file);
 end if;
 select coalesce(array_agg(distinct value::uuid),'{}') into file_ids from jsonb_array_elements_text(coalesce(payload->'attachments','[]'));
 if cardinality(file_ids)>5 then raise exception 'Maximum five attachments'; end if;
 if exists(select 1 from public.boss_room_messages where author_id=actor and request_id=(payload->>'request_id')::uuid) and (select count(*) from public.boss_room_attachments where message_id=(select id from public.boss_room_messages where author_id=actor and request_id=(payload->>'request_id')::uuid) and id=any(file_ids))<>cardinality(file_ids) then raise exception 'Retry attachments changed'; end if;
 if exists(select 1 from unnest(file_ids) x where not exists(select 1 from public.boss_room_attachments a where a.id=x and a.room_id=rid and a.owner_id=actor and a.status='ready' and (a.message_id is null or a.message_id=(select id from public.boss_room_messages where author_id=actor and request_id=(payload->>'request_id')::uuid)))) then raise exception 'Attachment not ready'; end if;
 result:=public.boss_rooms_delivery_v1(operation,payload);
 if exists(select 1 from public.boss_room_attachments where message_id=(result->>'id')::uuid and not id=any(file_ids)) then raise exception 'Retry attachments changed'; end if;
 update public.boss_room_attachments set message_id=(result->>'id')::uuid where id=any(file_ids);
 return result;
end;
$$;
revoke all on function public.boss_rooms_v1(text,jsonb) from public,anon;
grant execute on function public.boss_rooms_v1(text,jsonb) to authenticated;
commit;
