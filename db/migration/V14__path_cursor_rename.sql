-- V14: MySQL 8 把 CURSOR 当保留字。Hibernate ddl-auto=update 生成
--   cursor varchar(64)
-- 会在建 kb_path_snapshot 时语法失败。列改名为 path_cursor。
-- 项目没有 Flyway，本脚本不会自动跑；启动时 CodexMysqlReservedColumnFix 会做同样的 CHANGE。
-- 若表还不存在（上次 CREATE 失败），不必执行，Hibernate 会按实体直接建 path_cursor。

ALTER TABLE kb_path_snapshot
    CHANGE COLUMN `cursor` path_cursor VARCHAR(64) NULL;
