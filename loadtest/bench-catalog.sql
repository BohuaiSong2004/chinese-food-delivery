-- 1,000 synthetic 24h restaurants x 100 dishes = 100,000 dishes, for search benchmarks only.
insert into restaurants (slug, name, name_zh, cuisine, neighborhood, address, delivery_fee_cents, opens_at, closes_at, open_24h)
select 'bench-' || g, 'Bench Kitchen ' || g, '测试餐厅' || g,
       (array['sichuan','cantonese','hunan','shanghainese','taiwanese','dongbei','northwestern','shaanxi'])[1 + g % 8],
       'Benchville', g || ' Bench St', 300, '00:00', '00:00', true
from generate_series(1, 1000) g;
insert into menu_categories (restaurant_id, name, name_zh, position)
select id, 'Mains', '主食', 0 from restaurants where slug like 'bench-%';
insert into dishes (restaurant_id, category_id, name, name_zh, description, price_cents, tags, spice_level)
select r.id, c.id, p.en || ' ' || b.en || ' No. ' || r.id, p.zh || b.zh, 'Synthetic dish for the search benchmark.',
       800 + ((r.id * 7 + b.i * 13) % 2000),
       case when b.veg then array['vegetarian'] else array[]::text[] end || case when p.hot then array['spicy'] else array[]::text[] end,
       case when p.hot then 2 + (r.id % 2) else r.id % 2 end
from restaurants r
join menu_categories c on c.restaurant_id = r.id
cross join (values ('Spicy','香辣',true), ('Garlic','蒜蓉',false), ('Steamed','清蒸',false), ('Braised','红烧',false),
                   ('Salt and Pepper','椒盐',false), ('Sweet and Sour','糖醋',false), ('Cumin','孜然',true),
                   ('Scallion','葱油',false), ('Black Pepper','黑椒',true), ('Sichuan','川味',true)) p(en, zh, hot)
cross join (values (1,'Tofu','豆腐',true), (2,'Beef Noodles','牛肉面',false), (3,'Dumplings','饺子',false), (4,'Fish','鱼片',false),
                   (5,'Chicken','鸡丁',false), (6,'Pork Belly','五花肉',false), (7,'Shrimp','虾仁',false), (8,'Eggplant','茄子',true),
                   (9,'Lamb','羊肉',false), (10,'Fried Rice','炒饭',true)) b(i, en, zh, veg)
where r.slug like 'bench-%';
