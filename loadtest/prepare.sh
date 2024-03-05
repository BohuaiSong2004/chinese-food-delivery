#!/usr/bin/env bash
# Run from the repository root before peak.js. Sets one dish to exactly $LIMITED_STOCK baskets
# (the scarce item half the crowd wants), gives the other limited dishes plenty, and records the start.
set -euo pipefail
LIMITED_STOCK=${LIMITED_STOCK:-100}
q() { docker compose exec -T postgres psql -U wokflow -tAc "$1"; }
q "update dishes set daily_stock = $LIMITED_STOCK, stock_remaining = $LIMITED_STOCK where name = 'Shrimp & Pork Soup Dumplings'" >/dev/null
q "update dishes set daily_stock = 10000, stock_remaining = 10000 where daily_stock is not null and name <> 'Shrimp & Pork Soup Dumplings'" >/dev/null
q "create extension if not exists dblink" >/dev/null   # verify.sh joins the provider database for timings
q "select now()" > loadtest/.run-start
echo "prepared: scarce dish = $LIMITED_STOCK baskets, start=$(cat loadtest/.run-start)"
