#!/usr/bin/env bash
# Top-1 accuracy on queries with one right answer. Run with ES up, then with ES stopped (Postgres fallback).
BASE=${BASE_URL:-http://localhost:8000}
pass=0; total=0
while IFS='|' read -r q want; do
  total=$((total+1))
  got=$(curl -s -G "$BASE/api/search" --data-urlencode "q=$q" --data-urlencode size=1 | python3 -c "import sys,json; d=json.load(sys.stdin); print((d['hits'][0]['nameZh'] if d['hits'] else '-') + ' degraded=' + str(d['degraded']))")
  if [[ "$got" == "$want "* ]]; then pass=$((pass+1)); mark=ok; else mark=MISS; fi
  printf '%-16s -> %-28s %s\n' "$q" "$got" "$mark"
done <<'Q'
mapo tofu|麻婆豆腐
mapo tofo|麻婆豆腐
mapodoufu|麻婆豆腐
mpdf|麻婆豆腐
麻婆豆腐|麻婆豆腐
xiaolongbao|鲜肉小笼包
xlb|鲜肉小笼包
dan dan|担担面
dandanmian|担担面
ddm|担担面
congee fish|鱼片粥
yupianzhou|鱼片粥
wonton noodle|云吞面
wonton nodle|云吞面
roujiamo|肉夹馍
biangbiang|油泼扯面
Q
echo "top-1 accuracy: $pass/$total"
