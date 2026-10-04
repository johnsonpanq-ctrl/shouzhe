"""往模拟器的 shouzhe.db 注入测试数据（用于验证导入功能）"""
import sqlite3, sys, uuid, time, shutil, os, tempfile

src = sys.argv[1]          # 含 db/wal/shm 的目录
out = sys.argv[2]          # 输出目录

tmp = tempfile.mkdtemp()
for n in ("shouzhe.db", "shouzhe.db-wal", "shouzhe.db-shm"):
    p = os.path.join(src, n)
    if os.path.exists(p):
        shutil.copy2(p, os.path.join(tmp, n))

db = os.path.join(tmp, "shouzhe.db")
c = sqlite3.connect(db)
now = int(time.time() * 1000)

rows = [
    # (type, title, rawText, summary, amountCents, category, merchant, confirmed)
    ("LEDGER", "午饭",     None, "工作餐", 3855,  "餐饮", "星巴克", 0),
    ("LEDGER", "打车",     None, None,     1200,  "交通", "滴滴",   1),
    ("NOTE",   "读书记录", "今天读了《人月神话》，关于没有银弹的论述很受启发", None, None, None, None, None),
    ("TODO",   "给妈妈打电话", None, None, None, None, None, None),
]

for t, title, raw, summary, cents, cat, merchant, confirmed in rows:
    cur = c.execute(
        "INSERT INTO item (uuid,type,title,rawText,summary,sourceUrl,sourceApp,status,quality,createdAt,updatedAt,sourceImagePath) "
        "VALUES (?,?,?,?,?,NULL,NULL,'INBOX',NULL,?,?,NULL)",
        (str(uuid.uuid4()), t, title, raw, summary, now, now),
    )
    iid = cur.lastrowid
    if t == "LEDGER":
        c.execute(
            "INSERT INTO ledger_entry (itemId,amountCents,direction,category,merchant,occurredAt,confirmed) "
            "VALUES (?,?,?,?,?,?,?)",
            (iid, cents, "OUT", cat, merchant, now, confirmed),
        )
        c.execute("INSERT INTO item_tag (itemId,tagId,source) SELECT ?,id,'user' FROM tag WHERE name='餐饮'",
                  (iid,)) if c.execute("SELECT COUNT(*) FROM tag WHERE name='餐饮'").fetchone()[0] else None
    if t == "TODO":
        c.execute(
            "INSERT INTO todo_meta (itemId,dueAt,remindAt,remindState,priority,repeatRule,completedAt,snoozeCount) "
            "VALUES (?,?,?,'SCHEDULED',1,NULL,NULL,0)",
            (iid, now + 86400000, now + 86000000),
        )

c.commit()
print("注入完成，当前条目数 =", c.execute("SELECT COUNT(*) FROM item").fetchone()[0])
for r in c.execute("SELECT id,type,title FROM item ORDER BY id"):
    print("  ", r)
c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
c.close()

os.makedirs(out, exist_ok=True)
shutil.copy2(db, os.path.join(out, "shouzhe.db"))
print("已输出到", out)
