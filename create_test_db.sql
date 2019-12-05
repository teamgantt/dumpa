DROP DATABASE IF EXISTS dumpa_test_db_123;
CREATE DATABASE dumpa_test_db_123 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES on dumpa_test_db_123.* to 'dumpa_test'@'%' identified by 'dumpa_test';
GRANT REPLICATION CLIENT on *.* to 'dumpa_test'@'%';
GRANT REPLICATION SLAVE on *.* to 'dumpa_test'@'%';
Set GLOBAL binlog_format = 'ROW';
