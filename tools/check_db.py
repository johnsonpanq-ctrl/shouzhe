import sqlite3, sys

db = sys.argv[1]
c = sqlite3.connect(db)
print("user_version =", c.execute("PRAGMA user_version").fetchone()[0])
cols = [r[1] for r in c.execute("PRAGMA table_info(item)")]
print("sourceImagePath in item:", "sourceImagePath" in cols)
print("item count =", c.execute("SELECT COUNT(*) FROM item").fetchone()[0])
for r in c.execute("SELECT id,type,title,sourceImagePath FROM item ORDER BY id"):
    print("  item:", r)
for r in c.execute("SELECT itemId,amountCents,direction,category,confirmed FROM ledger_entry"):
    print("  ledger:", r)