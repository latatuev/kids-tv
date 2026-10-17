-- Kids TV / Гостья из будущего (1984)
-- Prepared 2026-10-17 after an outage of Supabase execute_sql.
-- Exact 5 official episodes: https://rutube.ru/plst/833152/ (Киностудия Горького).
-- This is an IDEMPOTENT, NON-PUBLISHING preparation. Xiaomi QA remains mandatory:
-- no blanket ad-free claim, no unverified automatic publication, no APK rebuild.
BEGIN;

DO $validation$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.kidstv_media
    WHERE title = 'Гостья из будущего' AND year = 1984 AND content_kind='video'
  ) THEN
    RAISE EXCEPTION 'Expected staged parent Гостья из будущего (1984) not found';
  END IF;
END $validation$;

INSERT INTO public.kidstv_collections(slug, title, kind, display, sort_order, enabled)
VALUES ('gostya-iz-budushchego-episodes',
        'Гостья из будущего — 5 серий', 'manual',
        '{"hidden":true,"content_kind":"rutube_series"}'::jsonb, 9006, true)
ON CONFLICT(slug) DO NOTHING;

UPDATE public.kidstv_media
SET source = source || jsonb_build_object(
      'rutube_collection', jsonb_build_object(
         'slug','gostya-iz-budushchego-episodes',
         'official_playlist_url','https://rutube.ru/plst/833152/',
         'channel','Киностудия Горького',
         'source_kind','rights_holder_official'
      )),
    meta = meta || jsonb_build_object(
      'curation_status','complete_five_episode_pack_pending_device_qa',
      'publication_status','hidden_pending_playback',
      'child_playback_ready',false,
      'content_format','five_part_tv_film',
      'series_structure',jsonb_build_object(
        'model','single_five_episode_series','episode_count',5,'provider','rutube_official'),
      'parent_note','Все 5 серий собраны с официального канала Киностудии Горького; воспроизведение во встроенном RUTUBE и отсутствие рекламы на Xiaomi НЕ проверены.',
      'kinopub_query_plan', jsonb_build_array('Гостья из будущего','Guest from the Future 1984','Гостья из будущего 5 серий')
    ),
    updated_at=now()
WHERE title='Гостья из будущего' AND year=1984 AND content_kind='video';

WITH parent AS (
    SELECT id FROM public.kidstv_media
    WHERE title='Гостья из будущего' AND year=1984 AND content_kind='video'
    ORDER BY created_at LIMIT 1
),
epis(n,rutube_id,runtime) AS (
  VALUES
    (1, 'a15b83ef60f859ea098b23ccf8892d00', '01:03:19'),
    (2, '21e21800edfba4d0141161edfee15046', '01:03:52'),
    (3, 'ba98f2c0d4fe0298a60d6f23fe92f13f', '01:03:11'),
    (4, '3e85aef595344aeaa938d7200b5a387d', '01:03:28'),
    (5, '67c2b07a882f182a338ce7893be01e4f', '01:03:11')
),
candidate AS (
  SELECT parent.id AS parent_id, e.n, e.rutube_id, e.runtime,
         'Гостья из будущего — ' || e.n::text || '-я серия' AS title
  FROM parent CROSS JOIN epis e
)
INSERT INTO public.kidstv_media (
    title, original_title, media_type, year, duration_minutes, enabled,
    kinopub_match_status, content_kind, content_role,
    source, meta, parent_media_id, sequence_no, sort_order
)
SELECT c.title,'Гостья из будущего','episode',1984,63,true,'unmatched','video','item',
   jsonb_build_object('rutube',jsonb_build_object(
      'video_id',c.rutube_id,'url','https://rutube.ru/video/'||c.rutube_id||'/',
      'channel','Киностудия Горького','source_kind','rights_holder_official',
      'playlist_url','https://rutube.ru/plst/833152/')),
   jsonb_build_object(
      'production_medium','live_action',
      'parent_series','gostya-iz-budushchego-episodes',
      'episode_number',c.n,
      'duration_label',c.runtime,
      'content_format','episode',
      'publication_status','hidden_pending_playback',
      'child_playback_ready',false,
      'child_scores',jsonb_build_object('leva',9.5,'sonya',6.5,'ilyusha',1),
      'playback_verification','rutube_official_playlist_match_pending_xiaomi',
      'source_rights_note','Ссылка на публичную запись правообладателя. Копирование файла отсутствует.',
      'parent_note','Серия доступна у Киностудии Горького. Интеграция RUTUBE на Xiaomi и реклама не проверены.'
   ),
   c.parent_id,c.n,100+c.n
FROM candidate c
WHERE NOT EXISTS (
  SELECT 1 FROM public.kidstv_media x
  WHERE x.parent_media_id=c.parent_id
    AND x.content_kind='video' AND x.sequence_no=c.n
);

WITH collection AS (
 SELECT id FROM public.kidstv_collections WHERE slug='gostya-iz-budushchego-episodes'
),
parent AS (
 SELECT id FROM public.kidstv_media WHERE title='Гостья из будущего'
 AND year=1984 AND content_kind='video' ORDER BY created_at LIMIT 1
),
rows AS (
 SELECT m.id AS media_id,m.sequence_no
 FROM public.kidstv_media m JOIN parent p ON p.id=m.parent_media_id
 WHERE m.sequence_no BETWEEN 1 AND 5
)
INSERT INTO public.kidstv_collection_items(collection_id,media_id,sort_order,enabled)
SELECT c.id,r.media_id,r.sequence_no,true FROM collection c CROSS JOIN rows r
ON CONFLICT(collection_id,media_id) DO NOTHING;

-- Intentionally NOT inserting the parent into an enabled child movie collection;
-- after successful Xiaomi playback test with no ads and reliable Back:
-- update published + child_playback_ready and link the parent to live-action-leva.
COMMIT;

SELECT m.title,
       (SELECT COUNT(*) FROM public.kidstv_media e WHERE e.parent_media_id=m.id AND e.content_kind='video') AS episode_count,
       m.meta->>'publication_status' AS publication_status,
       m.source->'rutube_collection'->>'official_playlist_url' AS official_playlist
FROM public.kidstv_media m
WHERE m.title='Гостья из будущего' AND m.year=1984 AND m.content_kind='video';
