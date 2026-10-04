INSERT INTO item (uuid,type,title,rawText,summary,sourceUrl,sourceApp,status,quality,createdAt,updatedAt)
VALUES ('u-ledger-1','LEDGER','烧饼',NULL,NULL,NULL,NULL,'INBOX',NULL,1759400000000,1759400000000);
INSERT INTO ledger_entry (itemId,amountCents,direction,category,merchant,occurredAt,confirmed)
VALUES ((SELECT id FROM item WHERE uuid='u-ledger-1'),100,'OUT','餐饮',NULL,1759400000000,0);

INSERT INTO item (uuid,type,title,rawText,summary,sourceUrl,sourceApp,status,quality,createdAt,updatedAt)
VALUES ('u-todo-1','TODO','明天去老丈人家',NULL,NULL,NULL,NULL,'INBOX',NULL,1759399900000,1759399900000);
INSERT INTO todo_meta (itemId,dueAt,remindAt,remindState,priority,repeatRule,completedAt,snoozeCount)
VALUES ((SELECT id FROM item WHERE uuid='u-todo-1'),1759485600000,1759485600000,'SCHEDULED',0,NULL,NULL,0);

INSERT INTO item (uuid,type,title,rawText,summary,sourceUrl,sourceApp,status,quality,createdAt,updatedAt)
VALUES ('u-article-1','ARTICLE','Flutter 状态管理：三种方案怎么选','小项目用 Provider 足够，中大型项目优先 Riverpod，BLoC 适合已有严格分层规范的团队。这是一段用来测试文章详情的正文，需要超过两百字才能被判定为抽取成功。状态管理是 Flutter 开发里最容易被过度设计的一环。不少团队在项目初期就引入重量级方案，复杂度上去了，可维护性反而下降。先看 Provider：学习曲线最平缓，本质上是对 InheritedWidget 的一层封装。再看 Riverpod，它解决了 Provider 的编译期安全问题。最后是 BLoC，适合大型团队协作。','对比 Provider、Riverpod、BLoC 的适用场景与迁移成本：小项目用 Provider 足够，中大型项目优先 Riverpod。','https://mp.weixin.qq.com/s/example','com.tencent.mm','INBOX','GOOD',1759380000000,1759380000000);
INSERT INTO article_meta (itemId,author,publishedAt,siteName,wordCount,coverImage)
VALUES ((SELECT id FROM item WHERE uuid='u-article-1'),'移动开发笔记',1759370000000,'微信公众号',260,NULL);

INSERT INTO item (uuid,type,title,rawText,summary,sourceUrl,sourceApp,status,quality,createdAt,updatedAt)
VALUES ('u-article-fail','ARTICLE','某篇反爬很严的文章',NULL,NULL,'https://mp.weixin.qq.com/s/failed',NULL,'INBOX','FAILED',1759375000000,1759375000000);
