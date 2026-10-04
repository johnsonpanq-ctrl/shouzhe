import sqlite3, sys, os, shutil, tempfile

# 把 db + -wal + -shm 放到同一临时目录，让 sqlite 自动回放 WAL
src_dir = sys.argv[1]
tmp = tempfile.mkdtemp()
for name in ("shouzhe.db", "shouzhe.db-wal", "shouzhe.db-shm"):
    p = os.path.join(src_dir, name)
    if os.path.exists(p):
        shutil.copy2(p, os.path.join(tmp, name))

db = os.path.join(tmp, "shouzhe.db")
c = sqlite3.connect(db)
print("user_version =", c.execute("PRAGMA user_version").fetchone()[0])
cols = [r[1] for r in c.execute("PRAGMA table_info(item)")]
print("sourceImagePath in item:", "sourceImagePath" in cols)
print("item count =", c.execute("SELECT COUNT(*) FROM item").fetchone()[0])
for r in c.execute("SELECT id,type,title,sourceImagePath FROM item ORDER BY id"):
    print("  item:", r)
for r in c.execute("SELECT itemId,amountCents,direction,category,merchant,confirmed FROM ledger_entry"):
    print("  ledger:", r)